package eu.xfsc.fc.core.service.dcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.eecc.dcp.api.access.PresentationAccessPolicy;
import de.eecc.dcp.message.PresentationResponseMessage;
import de.eecc.dcp.query.template.constructx.MembershipQueryDefinition;
import eu.xfsc.fc.core.dao.dcp.DcpPresentationRequestDefinition;
import eu.xfsc.fc.core.dao.dcp.DcpQueryKind;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DcpPresentationFacadeTest {

  @Mock
  private DcpAccessPolicyService accessPolicyService;

  @Mock
  private DcpPresentationRequestService requestService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private DcpPresentationFacade facade;

  @BeforeEach
  void setUp() {
    facade = new DcpPresentationFacade(accessPolicyService, requestService, objectMapper);
  }

  @Test
  void tryParse_detectsPresentationResponseMessage() {
    String body = """
        {
          "@context": ["https://w3id.org/dspace-dcp/v1.0/dcp.jsonld"],
          "type": "PresentationResponseMessage",
          "presentation": ["eyJhbGciOiJub25lIn0.eyJzdWIiOiJkaWQ6ZXhhbXBsZTpob2xkZXIifQ."]
        }
        """;

    Optional<PresentationResponseMessage> parsed =
        facade.tryParsePresentationResponse(body.getBytes(StandardCharsets.UTF_8), "application/json");

    assertTrue(parsed.isPresent());
    assertEquals("PresentationResponseMessage", parsed.get().type());
    assertEquals(1, parsed.get().presentation().size());
  }

  @Test
  void tryParse_ignoresOrdinaryCredentialJson() {
    String body = """
        {
          "@context": ["https://www.w3.org/ns/credentials/v2"],
          "type": ["VerifiableCredential"],
          "issuer": "did:example:issuer"
        }
        """;

    assertTrue(facade.tryParsePresentationResponse(
        body.getBytes(StandardCharsets.UTF_8), "application/json").isEmpty());
  }

  @Test
  void validateForPurpose_acceptsMembershipPresentationWithAllowAll() {
    when(requestService.requireQueryDefinition(DcpPurposes.POST_ASSETS))
        .thenReturn(MembershipQueryDefinition.INSTANCE);
    when(accessPolicyService.loadPolicy()).thenReturn(PresentationAccessPolicy.allowAll());

    String body = """
        {
          "@context": ["https://w3id.org/dspace-dcp/v1.0/dcp.jsonld"],
          "type": "PresentationResponseMessage",
          "presentation": [{
            "@context": ["https://www.w3.org/ns/credentials/v2"],
            "type": ["VerifiablePresentation"],
            "verifiableCredential": [{
              "@context": ["https://www.w3.org/ns/credentials/v2"],
              "type": ["VerifiableCredential", "MembershipCredential"],
              "issuer": "did:web:issuer.example",
              "credentialSubject": { "id": "did:web:holder.example" }
            }]
          }]
        }
        """;

    PresentationResponseMessage response = facade.tryParsePresentationResponse(
        body.getBytes(StandardCharsets.UTF_8), "application/json").orElseThrow();

    ValidatedDcpPresentation validated = facade.validateForPurpose(response, DcpPurposes.POST_ASSETS);

    assertEquals(1, validated.presentations().size());
    assertFalse(validated.presentations().get(0).content().length == 0);
  }

  @Test
  void toQueryDefinition_membershipEntity() {
    DcpPresentationRequestService svc =
        new DcpPresentationRequestService(null, objectMapper);
    DcpPresentationRequestDefinition entity = DcpPresentationRequestDefinition.builder()
        .id("asset-write")
        .name("Asset write")
        .purpose(DcpPurposes.POST_ASSETS)
        .queryKind(DcpQueryKind.MEMBERSHIP)
        .enabled(true)
        .build();

    assertEquals(
        MembershipQueryDefinition.INSTANCE.toQueryMessage().scope(),
        svc.toQueryDefinition(entity).toQueryMessage().scope());
  }
}
