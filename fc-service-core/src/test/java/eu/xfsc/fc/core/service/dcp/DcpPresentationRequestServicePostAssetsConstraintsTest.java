package eu.xfsc.fc.core.service.dcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.eecc.dcp.exception.DcpException;
import de.eecc.dcp.message.PresentationResponseMessage;
import de.eecc.dcp.query.PresentationQueryDefinition;
import de.eecc.dcp.query.template.constructx.MembershipQueryDefinition;
import eu.xfsc.fc.core.config.DcpProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class DcpPresentationRequestServicePostAssetsConstraintsTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void emptyPostAssetsConfig_leavesDefinitionUnchanged() {
    DcpProperties props = new DcpProperties();
    DcpPresentationRequestService svc =
        new DcpPresentationRequestService(null, objectMapper, props);

    PresentationQueryDefinition result = svc.applyPostAssetsPropertyConstraints(
        DcpPurposes.POST_ASSETS, MembershipQueryDefinition.INSTANCE);

    assertEquals(
        MembershipQueryDefinition.INSTANCE.toQueryMessage().scope(),
        result.toQueryMessage().scope());
    assertTrue(result.requiredIssuers().isEmpty());
  }

  @Test
  void requiredIssuer_addsIssuerConstraint() {
    DcpProperties props = new DcpProperties();
    props.getPostAssets().setRequiredIssuer("did:web:issuer.example");
    DcpPresentationRequestService svc =
        new DcpPresentationRequestService(null, objectMapper, props);

    PresentationQueryDefinition result = svc.applyPostAssetsPropertyConstraints(
        DcpPurposes.POST_ASSETS, MembershipQueryDefinition.INSTANCE);

    assertEquals(List.of("did:web:issuer.example"), result.requiredIssuers());
  }

  @Test
  void requiredCredentialType_rejectsWrongType() throws Exception {
    DcpProperties props = new DcpProperties();
    props.getPostAssets().setRequiredCredentialType("MembershipCredential");
    DcpPresentationRequestService svc =
        new DcpPresentationRequestService(null, objectMapper, props);

    PresentationQueryDefinition result = svc.applyPostAssetsPropertyConstraints(
        DcpPurposes.POST_ASSETS, MembershipQueryDefinition.INSTANCE);

    JsonNode presentation = objectMapper.readTree("""
        {
          "type": ["VerifiablePresentation"],
          "verifiableCredential": [{
            "type": ["VerifiableCredential", "OtherCredential"],
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

    DcpException ex = assertThrows(DcpException.class, () -> result.assertResponseMatches(response));
    assertTrue(ex.getMessage().contains("MembershipCredential"), ex.getMessage());
  }

  @Test
  void requiredCredentialType_acceptsMatchingType() throws Exception {
    DcpProperties props = new DcpProperties();
    props.getPostAssets().setRequiredCredentialType("MembershipCredential");
    props.getPostAssets().setRequiredIssuer("did:web:issuer.example");
    DcpPresentationRequestService svc =
        new DcpPresentationRequestService(null, objectMapper, props);

    PresentationQueryDefinition result = svc.applyPostAssetsPropertyConstraints(
        DcpPurposes.POST_ASSETS, MembershipQueryDefinition.INSTANCE);

    JsonNode presentation = objectMapper.readTree("""
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

    result.assertResponseMatches(response);
    assertEquals(List.of("did:web:issuer.example"), result.requiredIssuers());
  }

  @Test
  void nonPostAssetsPurpose_ignoresPostAssetsConfig() {
    DcpProperties props = new DcpProperties();
    props.getPostAssets().setRequiredIssuer("did:web:issuer.example");
    DcpPresentationRequestService svc =
        new DcpPresentationRequestService(null, objectMapper, props);

    PresentationQueryDefinition result = svc.applyPostAssetsPropertyConstraints(
        "OTHER", MembershipQueryDefinition.INSTANCE);

    assertTrue(result.requiredIssuers().isEmpty());
  }
}
