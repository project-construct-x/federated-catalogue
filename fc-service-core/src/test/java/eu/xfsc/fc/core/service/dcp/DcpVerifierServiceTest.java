package eu.xfsc.fc.core.service.dcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.eecc.dcp.exception.DcpException;
import de.eecc.dcp.message.PresentationQueryMessage;
import de.eecc.dcp.message.PresentationResponseMessage;
import de.eecc.dcp.query.template.constructx.MembershipQueryDefinition;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DcpVerifierServiceTest {

  private static final String HOLDER = "did:web:holder.example";
  private static final String CS_URL = "https://cs.holder.example";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock
  private SelfIssuedIdTokenValidator siTokenValidator;
  @Mock
  private CredentialServiceDiscovery credentialServiceDiscovery;
  @Mock
  private DcpPresentationRequestService requestService;
  @Mock
  private VerifierSiTokenFactory verifierSiTokenFactory;
  @Mock
  private CredentialServiceClient credentialServiceClient;

  private DcpVerifierService service;

  @BeforeEach
  void setUp() {
    service = new DcpVerifierService(
        siTokenValidator,
        credentialServiceDiscovery,
        requestService,
        verifierSiTokenFactory,
        credentialServiceClient);
  }

  @Test
  void pullPresentations_successWithMatchingSubject() throws Exception {
    stubClientToken();
    when(credentialServiceDiscovery.resolveCredentialServiceUrl(HOLDER)).thenReturn(CS_URL);
    when(requestService.requireQueryDefinition(DcpPurposes.POST_ASSETS))
        .thenReturn(MembershipQueryDefinition.INSTANCE);
    when(verifierSiTokenFactory.create(eq(CS_URL), eq("opaque-cs-token")))
        .thenReturn("verifier-si");

    JsonNode presentation = MAPPER.readTree("""
        {
          "type": ["VerifiablePresentation"],
          "verifiableCredential": [{
            "type": ["VerifiableCredential", "MembershipCredential"],
            "issuer": "did:web:issuer.example",
            "credentialSubject": { "id": "did:web:holder.example" }
          }]
        }
        """);
    PresentationResponseMessage response = new PresentationResponseMessage(
        List.of("https://w3id.org/dspace-dcp/v1.0/dcp.jsonld"),
        "PresentationResponseMessage",
        List.of(presentation),
        null);
    when(credentialServiceClient.queryPresentations(eq(CS_URL), any(PresentationQueryMessage.class),
        eq("verifier-si"))).thenReturn(response);

    PresentationResponseMessage result = service.pullPresentations("Bearer client-si", null);

    assertEquals("PresentationResponseMessage", result.type());
    assertEquals(1, result.presentation().size());
    verify(credentialServiceClient).queryPresentations(eq(CS_URL), any(), eq("verifier-si"));
  }

  @Test
  void pullPresentations_rejectsHolderBindingMismatch() throws Exception {
    stubClientToken();
    when(credentialServiceDiscovery.resolveCredentialServiceUrl(HOLDER)).thenReturn(CS_URL);
    when(requestService.requireQueryDefinition(DcpPurposes.POST_ASSETS))
        .thenReturn(MembershipQueryDefinition.INSTANCE);
    when(verifierSiTokenFactory.create(eq(CS_URL), eq("opaque-cs-token")))
        .thenReturn("verifier-si");

    JsonNode presentation = MAPPER.readTree("""
        {
          "type": ["VerifiablePresentation"],
          "verifiableCredential": [{
            "type": ["VerifiableCredential", "MembershipCredential"],
            "issuer": "did:web:issuer.example",
            "credentialSubject": { "id": "did:web:attacker.example" }
          }]
        }
        """);
    PresentationResponseMessage response = new PresentationResponseMessage(
        List.of("https://w3id.org/dspace-dcp/v1.0/dcp.jsonld"),
        "PresentationResponseMessage",
        List.of(presentation),
        null);
    when(credentialServiceClient.queryPresentations(eq(CS_URL), any(PresentationQueryMessage.class),
        eq("verifier-si"))).thenReturn(response);

    DcpException ex = assertThrows(DcpException.class,
        () -> service.pullPresentations("Bearer client-si", DcpPurposes.POST_ASSETS));
    assertTrue(
        ex.getMessage().toLowerCase().contains("subject")
            || ex.getMessage().toLowerCase().contains("holder"),
        ex.getMessage());
  }

  private void stubClientToken() {
    when(siTokenValidator.validate("Bearer client-si"))
        .thenReturn(new ValidatedSelfIssuedIdToken(
            HOLDER, "https://catalogue.example", "jti-1", "opaque-cs-token", "client-si"));
  }
}
