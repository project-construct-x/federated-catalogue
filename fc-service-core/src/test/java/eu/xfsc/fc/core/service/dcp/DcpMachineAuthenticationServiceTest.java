package eu.xfsc.fc.core.service.dcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.eecc.dcp.message.PresentationResponseMessage;
import eu.xfsc.fc.core.config.DcpProperties;
import eu.xfsc.fc.core.exception.VerificationException;
import eu.xfsc.fc.core.service.verification.VerificationService;
import java.util.List;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import eu.xfsc.fc.core.pojo.CredentialVerificationResult;
import eu.xfsc.fc.core.pojo.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;

class DcpMachineAuthenticationServiceTest {
  private static final String HOLDER = "did:web:holder.example";
  private static final String ISSUER = "did:web:issuer.example";
  private final DcpVerifierService verifier = mock(DcpVerifierService.class);
  private final VerificationService verification = mock(VerificationService.class);
  private final DcpProperties properties = new DcpProperties();
  private final DcpMachineAuthenticationService service =
      new DcpMachineAuthenticationService(verifier, verification, properties);

  @BeforeEach
  void configure() {
    properties.setAudience("https://catalogue.example");
    properties.getPostAssets().setRequiredIssuer(ISSUER);
  }

  @Test
  void establishesIdentityOnlyAfterStrictVerification() throws Exception {
    response(ISSUER, HOLDER, "MembershipCredential");
    when(verification.verifyCredential(any(), eq(true), eq(true), eq(true), eq(false)))
        .thenReturn(new CredentialVerificationResult(Instant.now(), "ACTIVE", ISSUER, Instant.now(), HOLDER,
            List.of(), List.of(new Validator(HOLDER + "#key", null, null),
                new Validator(ISSUER + "#key", null, null)), null, null));
    var identity = service.authenticateAssetWrite("Bearer si");
    assertEquals(HOLDER, identity.participantDid());
    assertEquals(HOLDER, identity.actorDid());
    verify(verification).verifyCredential(any(), eq(true), eq(true), eq(true), eq(false));
  }

  @Test
  void rejectsCryptographicVerificationFailureWithoutExposingPayload() throws Exception {
    response(ISSUER, HOLDER, "MembershipCredential");
    when(verification.verifyCredential(any(), eq(true), eq(true), eq(true), eq(false)))
        .thenThrow(new VerificationException("sensitive credential body"));
    var error = assertThrows(BadCredentialsException.class, () -> service.authenticateAssetWrite("Bearer si"));
    assertEquals("DCP membership authentication failed", error.getMessage());
    assertNull(error.getCause());
  }

  @Test
  void rejectsWrongIssuerTypeAndSubject() throws Exception {
    response("did:web:untrusted", HOLDER, "MembershipCredential");
    assertThrows(BadCredentialsException.class, () -> service.authenticateAssetWrite("Bearer si"));
    response(ISSUER, HOLDER, "OtherCredential");
    assertThrows(BadCredentialsException.class, () -> service.authenticateAssetWrite("Bearer si"));
    response(ISSUER, "did:web:other", "MembershipCredential");
    assertThrows(BadCredentialsException.class, () -> service.authenticateAssetWrite("Bearer si"));
    verifyNoInteractions(verification);
  }

  @Test
  void rejectsMissingTrustConfigurationBeforePull() {
    properties.setAudience("");
    assertThrows(AuthenticationServiceException.class, () -> service.authenticateAssetWrite("Bearer si"));
    properties.setAudience("https://catalogue.example");
    properties.getPostAssets().setRequiredIssuer("");
    assertThrows(AuthenticationServiceException.class, () -> service.authenticateAssetWrite("Bearer si"));
    verifyNoInteractions(verifier, verification);
  }

  @Test
  void rejectsMissingOrAmbiguousPresentations() throws Exception {
    var response = response(ISSUER, HOLDER, "MembershipCredential");
    when(verifier.pullForAuthentication(any(), any())).thenReturn(new DcpVerifierService.PulledPresentation(
        HOLDER, new PresentationResponseMessage(response.context(), response.type(), List.of(), null)));
    assertThrows(BadCredentialsException.class, () -> service.authenticateAssetWrite("Bearer si"));
    when(verifier.pullForAuthentication(any(), any())).thenReturn(new DcpVerifierService.PulledPresentation(
        HOLDER, new PresentationResponseMessage(response.context(), response.type(),
            List.of(response.presentation().getFirst(), response.presentation().getFirst()), null)));
    assertThrows(BadCredentialsException.class, () -> service.authenticateAssetWrite("Bearer si"));
    verifyNoInteractions(verification);
  }

  private PresentationResponseMessage response(String issuer, String subject, String type) throws Exception {
    var mapper = new ObjectMapper();
    String vc = jwt("""
        {"iss":"%s", "vc":{
          "type":["VerifiableCredential","%s"], "issuer":"%s", "credentialSubject":{"id":"%s"}
        }}
        """.formatted(issuer, type, issuer, subject));
    var vp = mapper.getNodeFactory().textNode(jwt("""
        {"iss":"%s", "vp":{"type":["VerifiablePresentation"], "verifiableCredential":[%s]}}
        """.formatted(HOLDER, mapper.writeValueAsString(vc))));
    var response = new PresentationResponseMessage(List.of("https://w3id.org/dspace-dcp/v1.0/dcp.jsonld"),
        "PresentationResponseMessage", List.of(vp), null);
    when(verifier.pullForAuthentication(any(), any()))
        .thenReturn(new DcpVerifierService.PulledPresentation(HOLDER, response));
    return response;
  }

  // Syntactic JWT fixtures only: the cryptographic verification boundary is mocked explicitly.
  private String jwt(String payload) {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    return encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
        + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".c2ln";
  }

  @Test
  void neverAcceptsAnUnverifiedOrNonCredentialResult() throws Exception {
    response(ISSUER, HOLDER, "MembershipCredential");
    assertThrows(BadCredentialsException.class, () -> service.authenticateAssetWrite("Bearer si"));
  }
}
