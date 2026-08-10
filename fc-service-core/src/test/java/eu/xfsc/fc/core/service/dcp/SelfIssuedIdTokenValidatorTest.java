package eu.xfsc.fc.core.service.dcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.eecc.dcp.exception.DcpException;
import de.eecc.dcp.exception.InvalidSelfIssuedIdToken;
import eu.xfsc.fc.core.config.DcpProperties;
import eu.xfsc.fc.core.service.resolve.DidDocumentResolver;
import foundation.identity.did.DIDDocument;
import foundation.identity.did.VerificationMethod;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SelfIssuedIdTokenValidatorTest {

  private static final String HOLDER = "did:web:holder.example";
  private static final String KID = HOLDER + "#key-1";
  private static final String AUDIENCE = "https://catalogue.example";

  @Mock
  private DidDocumentResolver didResolver;

  private OctetKeyPair keyPair;
  private SelfIssuedIdTokenValidator validator;

  @BeforeEach
  void setUp() throws Exception {
    keyPair = new OctetKeyPairGenerator(Curve.Ed25519).keyID(KID).generate();
    DcpProperties properties = new DcpProperties();
    properties.setAudience(AUDIENCE);
    properties.setJtiTtl(Duration.ofMinutes(5));
    validator = new SelfIssuedIdTokenValidator(didResolver, properties);
  }

  @Test
  void validate_acceptsValidSiToken() throws Exception {
    mockCapabilityInvocation();
    String jwt = sign(HOLDER, HOLDER, AUDIENCE, UUID.randomUUID().toString(), Instant.now().plusSeconds(300));

    ValidatedSelfIssuedIdToken result = validator.validate("Bearer " + jwt);

    assertEquals(HOLDER, result.holderDid());
    assertEquals(AUDIENCE, result.audience());
  }

  @Test
  void validate_rejectsIssNotEqualSub() throws Exception {
    String jwt = sign(HOLDER, "did:web:other.example", AUDIENCE, UUID.randomUUID().toString(),
        Instant.now().plusSeconds(300));

    DcpException ex = assertThrows(DcpException.class, () -> validator.validate(jwt));
    assertTrue(ex.error() instanceof InvalidSelfIssuedIdToken);
    assertTrue(ex.getMessage().contains("iss must equal sub"));
  }

  @Test
  void validate_rejectsWrongAudience() throws Exception {
    String jwt = sign(HOLDER, HOLDER, "https://wrong.example", UUID.randomUUID().toString(),
        Instant.now().plusSeconds(300));

    DcpException ex = assertThrows(DcpException.class, () -> validator.validate(jwt));
    assertTrue(ex.getMessage().contains("aud does not match"));
  }

  @Test
  void validate_rejectsReplayedJti() throws Exception {
    mockCapabilityInvocation();
    String jti = UUID.randomUUID().toString();
    String jwt = sign(HOLDER, HOLDER, AUDIENCE, jti, Instant.now().plusSeconds(300));

    validator.validate(jwt);
    DcpException ex = assertThrows(DcpException.class, () -> validator.validate(jwt));
    assertTrue(ex.getMessage().contains("jti replay"));
  }

  @Test
  void validate_rejectsAssertionMethodOnlyKey() throws Exception {
    DIDDocument doc = mock(DIDDocument.class);
    when(doc.getCapabilityInvocationVerificationMethodsDereferenced()).thenReturn(List.of());
    when(didResolver.resolveDidDocument(HOLDER)).thenReturn(doc);

    String jwt = sign(HOLDER, HOLDER, AUDIENCE, UUID.randomUUID().toString(), Instant.now().plusSeconds(300));

    DcpException ex = assertThrows(DcpException.class, () -> validator.validate(jwt));
    assertTrue(ex.getMessage().contains("capabilityInvocation"));
  }

  @Test
  void validate_rejectsMissingBearer() {
    DcpException ex = assertThrows(DcpException.class, () -> validator.validate(null));
    assertTrue(ex.getMessage().contains("required"));
  }

  private void mockCapabilityInvocation() {
    VerificationMethod vm = mock(VerificationMethod.class);
    when(vm.getId()).thenReturn(URI.create(KID));
    when(vm.getPublicKeyJwk()).thenReturn(keyPair.toPublicJWK().toJSONObject());

    DIDDocument doc = mock(DIDDocument.class);
    when(doc.getCapabilityInvocationVerificationMethodsDereferenced()).thenReturn(List.of(vm));
    when(didResolver.resolveDidDocument(HOLDER)).thenReturn(doc);
  }

  private String sign(String iss, String sub, String aud, String jti, Instant exp) throws Exception {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer(iss)
        .subject(sub)
        .audience(aud)
        .jwtID(jti)
        .issueTime(Date.from(Instant.now()))
        .expirationTime(Date.from(exp))
        .build();
    SignedJWT jwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(KID).build(),
        claims);
    jwt.sign(new Ed25519Signer(keyPair));
    return jwt.serialize();
  }
}
