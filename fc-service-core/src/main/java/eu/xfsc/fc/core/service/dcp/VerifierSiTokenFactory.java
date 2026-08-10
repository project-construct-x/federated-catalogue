package eu.xfsc.fc.core.service.dcp;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import eu.xfsc.fc.core.config.DcpProperties;
import eu.xfsc.fc.core.exception.ServerException;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Mints short-lived verifier Self-Issued ID Tokens for outbound Credential Service calls.
 */
@Slf4j
@Component
public class VerifierSiTokenFactory {

  private final DcpProperties properties;
  private final JWK signingKey;

  public VerifierSiTokenFactory(DcpProperties properties) {
    this.properties = properties;
    this.signingKey = parseSigningKey(properties.getVerifierSigningKey());
  }

  /**
   * Creates a verifier SI token with {@code aud} set to the Credential Service URL.
   * Forwards the client's opaque {@code token} claim when present.
   */
  public String create(String credentialServiceUrl, String opaqueTokenToForward) {
    String verifierDid = properties.getVerifierDid();
    if (verifierDid == null || verifierDid.isBlank()) {
      throw new ServerException(
          "federated-catalogue.dcp.verifier-did is required to mint verifier SI tokens");
    }
    if (signingKey == null) {
      throw new ServerException(
          "federated-catalogue.dcp.verifier-signing-key is required to mint verifier SI tokens");
    }

    Instant now = Instant.now();
    Instant exp = now.plus(properties.getVerifierTokenTtl());
    String jti = UUID.randomUUID().toString();
    String kid = signingKey.getKeyID();
    if (kid == null || kid.isBlank()) {
      kid = verifierDid + "#key-1";
    } else if (kid.startsWith("#")) {
      kid = verifierDid + kid;
    }

    try {
      JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
          .issuer(verifierDid)
          .subject(verifierDid)
          .audience(credentialServiceUrl)
          .jwtID(jti)
          .issueTime(Date.from(now))
          .notBeforeTime(Date.from(now))
          .expirationTime(Date.from(exp));
      if (opaqueTokenToForward != null && !opaqueTokenToForward.isBlank()) {
        claims.claim("token", opaqueTokenToForward);
      }

      JWSHeader header = new JWSHeader.Builder(algorithmFor(signingKey))
          .keyID(kid)
          .build();
      SignedJWT jwt = new SignedJWT(header, claims.build());
      jwt.sign(signerFor(signingKey));
      log.debug("create; minted verifier SI token for aud={}, jti={}", credentialServiceUrl, jti);
      return jwt.serialize();
    } catch (JOSEException ex) {
      throw new ServerException("Failed to mint verifier SI token: " + ex.getMessage(), ex);
    }
  }

  private static JWK parseSigningKey(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return JWK.parse(json);
    } catch (ParseException ex) {
      throw new ServerException(
          "Invalid federated-catalogue.dcp.verifier-signing-key JWK: " + ex.getMessage(), ex);
    }
  }

  private static JWSAlgorithm algorithmFor(JWK jwk) {
    if (jwk instanceof OctetKeyPair) {
      return JWSAlgorithm.EdDSA;
    }
    if (jwk instanceof RSAKey) {
      return JWSAlgorithm.RS256;
    }
    if (jwk instanceof ECKey ecKey) {
      if ("P-256".equals(ecKey.getCurve().getName())) {
        return JWSAlgorithm.ES256;
      }
      if ("P-384".equals(ecKey.getCurve().getName())) {
        return JWSAlgorithm.ES384;
      }
      return JWSAlgorithm.ES256;
    }
    throw new ServerException("Unsupported verifier signing key type: " + jwk.getKeyType());
  }

  private static JWSSigner signerFor(JWK jwk) throws JOSEException {
    if (jwk instanceof OctetKeyPair okp) {
      return new Ed25519Signer(okp);
    }
    if (jwk instanceof RSAKey rsaKey) {
      return new RSASSASigner(rsaKey);
    }
    if (jwk instanceof ECKey ecKey) {
      return new ECDSASigner(ecKey);
    }
    throw new ServerException("Unsupported verifier signing key type: " + jwk.getKeyType());
  }
}
