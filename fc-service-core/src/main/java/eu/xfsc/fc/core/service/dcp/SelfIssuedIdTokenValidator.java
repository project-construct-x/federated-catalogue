package eu.xfsc.fc.core.service.dcp;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import de.eecc.dcp.exception.DcpException;
import de.eecc.dcp.exception.InvalidSelfIssuedIdToken;
import eu.xfsc.fc.core.config.DcpProperties;
import eu.xfsc.fc.core.service.resolve.DidDocumentResolver;
import foundation.identity.did.DIDDocument;
import foundation.identity.did.VerificationMethod;
import java.security.PublicKey;
import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Validates DCP Self-Issued ID Tokens per the Eclipse DCP SI-token rules:
 * {@code iss == sub}, signature against {@code capabilityInvocation}, {@code aud}/{@code exp}/{@code jti}.
 */
@Slf4j
@Component
public class SelfIssuedIdTokenValidator {

  private static final DefaultJWSVerifierFactory JWS_VERIFIER_FACTORY = new DefaultJWSVerifierFactory();

  private final DidDocumentResolver didResolver;
  private final DcpProperties properties;
  private final Cache<String, Boolean> jtiCache;

  public SelfIssuedIdTokenValidator(DidDocumentResolver didResolver, DcpProperties properties) {
    this.didResolver = didResolver;
    this.properties = properties;
    this.jtiCache = Caffeine.newBuilder()
        .expireAfterWrite(properties.getJtiTtl())
        .maximumSize(100_000)
        .build();
  }

  /**
   * Validates a Bearer token value (with or without the {@code Bearer } prefix).
   */
  public ValidatedSelfIssuedIdToken validate(String authorizationHeaderOrToken) {
    String compact = extractBearer(authorizationHeaderOrToken);
    SignedJWT signedJwt = parseJwt(compact);
    JWSHeader header = signedJwt.getHeader();
    rejectAlgNone(header);

    JWTClaimsSet claims = parseClaims(signedJwt);
    String iss = requireClaim(claims.getIssuer(), "iss");
    String sub = requireClaim(claims.getSubject(), "sub");
    if (!iss.equals(sub)) {
      throw invalid("iss must equal sub (got iss='" + iss + "', sub='" + sub + "')");
    }

    verifyTimeClaims(claims);
    verifyAudience(claims);
    String jti = requireClaim(claims.getJWTID(), "jti");
    rememberJti(jti);

    DIDDocument didDoc = didResolver.resolveDidDocument(iss);
    List<VerificationMethod> methods = didDoc.getCapabilityInvocationVerificationMethodsDereferenced();
    if (methods == null || methods.isEmpty()) {
      throw invalid("No capabilityInvocation verification methods in DID document for: " + iss);
    }

    String kid = header.getKeyID();
    if (kid != null && kid.startsWith("#")) {
      kid = iss + kid;
    }
    verifySignature(signedJwt, header, kid, iss, methods);

    String opaqueToken = claimAsString(claims, "token");
    String aud = firstAudience(claims);
    log.debug("validate; accepted SI token for holderDid={}, jti={}", iss, jti);
    return new ValidatedSelfIssuedIdToken(iss, aud, jti, opaqueToken, compact);
  }

  private void verifyAudience(JWTClaimsSet claims) {
    String expected = properties.getAudience();
    if (expected == null || expected.isBlank()) {
      return;
    }
    List<String> audiences = claims.getAudience();
    if (audiences == null || audiences.stream().noneMatch(expected::equals)) {
      throw invalid("aud does not match catalogue audience '" + expected + "'");
    }
  }

  private void verifyTimeClaims(JWTClaimsSet claims) {
    try {
      new DefaultJWTClaimsVerifier<>(null, Set.of()).verify(claims, null);
    } catch (BadJWTException ex) {
      throw invalid("JWT claims validation failed: " + ex.getMessage());
    }
  }

  private void rememberJti(String jti) {
    Boolean previous = jtiCache.asMap().putIfAbsent(jti, Boolean.TRUE);
    if (previous != null) {
      throw invalid("jti replay detected: " + jti);
    }
  }

  private void verifySignature(
      SignedJWT signedJwt,
      JWSHeader header,
      String kid,
      String iss,
      List<VerificationMethod> methods) {
    if (kid != null) {
      VerificationMethod matched = methods.stream()
          .filter(vm -> kid.equals(methodId(vm)))
          .findFirst()
          .orElse(null);
      if (matched == null) {
        throw invalid("kid '" + kid + "' not found in capabilityInvocation");
      }
      JWK jwk = resolveJwk(matched);
      verifyWithJwk(signedJwt, header, jwk, kid);
      return;
    }

    for (VerificationMethod method : methods) {
      try {
        JWK jwk = resolveJwk(method);
        if (verifyQuietly(signedJwt, header, jwk)) {
          return;
        }
      } catch (RuntimeException ex) {
        log.debug("verifySignature; skipping method: {}", ex.getMessage());
      }
    }
    throw invalid("SI token signature verification failed for issuer: " + iss);
  }

  private void verifyWithJwk(SignedJWT signedJwt, JWSHeader header, JWK jwk, String kid) {
    try {
      JWSVerifier verifier = createVerifier(header, jwk);
      if (!signedJwt.verify(verifier)) {
        throw invalid("SI token signature verification failed for kid '" + kid + "'");
      }
    } catch (JOSEException ex) {
      throw invalid("SI token signature error: " + ex.getMessage());
    }
  }

  private boolean verifyQuietly(SignedJWT signedJwt, JWSHeader header, JWK jwk) {
    try {
      return signedJwt.verify(createVerifier(header, jwk));
    } catch (JOSEException | RuntimeException ex) {
      return false;
    }
  }

  private JWSVerifier createVerifier(JWSHeader header, JWK jwk) throws JOSEException {
    if (jwk instanceof OctetKeyPair okp) {
      return new Ed25519Verifier(okp.toPublicJWK());
    }
    PublicKey publicKey;
    if (jwk instanceof RSAKey rsaKey) {
      publicKey = rsaKey.toPublicKey();
    } else if (jwk instanceof ECKey ecKey) {
      publicKey = ecKey.toPublicKey();
    } else {
      throw invalid("unsupported JWK key type: " + jwk.getKeyType());
    }
    return JWS_VERIFIER_FACTORY.createJWSVerifier(header, publicKey);
  }

  private JWK resolveJwk(VerificationMethod method) {
    Object raw = method.getPublicKeyJwk();
    if (!(raw instanceof Map<?, ?> map)) {
      throw invalid("unsupported key format — only publicKeyJwk is supported");
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> jwkMap = (Map<String, Object>) map;
      return JWK.parse(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(jwkMap));
    } catch (ParseException | com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw invalid("failed to parse publicKeyJwk: " + ex.getMessage());
    }
  }

  private static String extractBearer(String value) {
    if (value == null || value.isBlank()) {
      throw invalid("Authorization Bearer Self-Issued ID Token is required");
    }
    String trimmed = value.strip();
    if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
      trimmed = trimmed.substring(7).strip();
    }
    if (trimmed.isEmpty()) {
      throw invalid("Authorization Bearer Self-Issued ID Token is required");
    }
    return trimmed;
  }

  private static SignedJWT parseJwt(String compact) {
    try {
      return SignedJWT.parse(compact);
    } catch (ParseException ex) {
      throw invalid("malformed SI token: " + ex.getMessage());
    }
  }

  private static JWTClaimsSet parseClaims(SignedJWT signedJwt) {
    try {
      return signedJwt.getJWTClaimsSet();
    } catch (ParseException ex) {
      throw invalid("malformed SI token claims: " + ex.getMessage());
    }
  }

  private static void rejectAlgNone(JWSHeader header) {
    if (header.getAlgorithm() == null) {
      throw invalid("SI token missing 'alg' header");
    }
    if ("none".equalsIgnoreCase(header.getAlgorithm().getName())) {
      throw invalid("SI token alg:none is not permitted");
    }
  }

  private static String requireClaim(String value, String name) {
    if (value == null || value.isBlank()) {
      throw invalid("SI token missing '" + name + "' claim");
    }
    return value;
  }

  private static String claimAsString(JWTClaimsSet claims, String name) {
    Object value = claims.getClaim(name);
    return value == null ? null : String.valueOf(value);
  }

  private static String firstAudience(JWTClaimsSet claims) {
    List<String> audiences = claims.getAudience();
    if (audiences == null || audiences.isEmpty()) {
      return null;
    }
    return audiences.get(0);
  }

  private static String methodId(VerificationMethod method) {
    return method.getId() != null ? method.getId().toString() : null;
  }

  private static DcpException invalid(String detail) {
    return new DcpException(new InvalidSelfIssuedIdToken(detail));
  }
}
