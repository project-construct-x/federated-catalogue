package eu.xfsc.fc.core.service.dcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.eecc.dcp.Constants;
import de.eecc.dcp.api.DcpOptions;
import de.eecc.dcp.api.DcpPresentation;
import de.eecc.dcp.claims.PresentationClaims;
import de.eecc.dcp.exception.DcpException;
import de.eecc.dcp.message.PresentationResponseMessage;
import de.eecc.dcp.query.PresentationQueryDefinition;
import de.eecc.dcp.vp.PresentationParser;
import eu.xfsc.fc.core.exception.ClientException;
import eu.xfsc.fc.core.service.verification.VerificationConstants;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Catalogue façade over EECC {@link DcpPresentation}: detects inbound
 * {@link PresentationResponseMessage} bodies, validates them with the package against a stored
 * presentation request definition, and materialises presentation payloads for asset ingest.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DcpPresentationFacade {

  private final DcpPresentationRequestService requestService;
  private final ObjectMapper objectMapper;

  /**
   * Returns a parsed {@link PresentationResponseMessage} when the body is a DCP presentation
   * response; empty otherwise (ordinary VC/VP/RDF upload).
   */
  public Optional<PresentationResponseMessage> tryParsePresentationResponse(byte[] content, String contentType) {
    if (content == null || content.length == 0) {
      return Optional.empty();
    }
    String normalized = contentType == null ? "" : contentType.strip().toLowerCase();
    if (!normalized.isEmpty()
        && !normalized.startsWith("application/json")
        && !normalized.startsWith("application/ld+json")
        && !normalized.contains("+json")) {
      return Optional.empty();
    }

    try {
      JsonNode root = objectMapper.readTree(content);
      if (root == null || !root.isObject()) {
        return Optional.empty();
      }
      if (!ValidatedDcpPresentation.isPresentationResponseType(root.get("type"))) {
        return Optional.empty();
      }
      PresentationResponseMessage message = objectMapper.treeToValue(root, PresentationResponseMessage.class);
      return Optional.of(message);
    } catch (java.io.IOException ex) {
      log.debug("tryParsePresentationResponse; not a DCP presentation response: {}", ex.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Validates {@code response} against the enabled request definition for {@code purpose} using
   * EECC {@link DcpPresentation#verifyAndExtractClaims}, then materialises presentation entries
   * for credential ingest.
   */
  public ValidatedDcpPresentation validateForPurpose(PresentationResponseMessage response, String purpose) {
    PresentationQueryDefinition definition = requestService.requireQueryDefinition(purpose);
    DcpPresentation dcp = DcpPresentation.create(DcpOptions.builder().build());

    try {
      PresentationClaims claims = dcp.verifyAndExtractClaims(definition, response);
      log.debug("validateForPurpose; purpose={}, claimsPresent={}", purpose, claims != null);

      List<ValidatedDcpPresentation.PresentationPayload> payloads = materialisePresentations(response);
      if (payloads.isEmpty()) {
        throw new ClientException("DCP PresentationResponseMessage contains no usable presentations");
      }
      return new ValidatedDcpPresentation(response, payloads);
    } catch (DcpException ex) {
      throw new ClientException("DCP presentation rejected: " + ex.getMessage(), ex);
    }
  }

  private List<ValidatedDcpPresentation.PresentationPayload> materialisePresentations(
      PresentationResponseMessage response) {
    List<ValidatedDcpPresentation.PresentationPayload> payloads = new ArrayList<>();
    List<JsonNode> presentations = response.presentation();
    if (presentations == null) {
      return payloads;
    }
    for (JsonNode presentation : presentations) {
      if (presentation == null || presentation.isNull()) {
        continue;
      }
      try {
        payloads.add(toPayload(presentation));
      } catch (JsonProcessingException ex) {
        throw new ClientException("Failed to serialise DCP presentation entry: " + ex.getMessage(), ex);
      }
    }
    return payloads;
  }

  private ValidatedDcpPresentation.PresentationPayload toPayload(JsonNode presentation)
      throws JsonProcessingException {
    if (presentation.isTextual()) {
      String text = presentation.asText();
      String mediaType = PresentationParser.isCompactJwt(text)
          ? VerificationConstants.MEDIA_TYPE_VP_JWT
          : VerificationConstants.MEDIA_TYPE_VP_LD_JSON;
      return new ValidatedDcpPresentation.PresentationPayload(
          text.getBytes(StandardCharsets.UTF_8), mediaType, presentation);
    }
    byte[] bytes = objectMapper.writeValueAsBytes(presentation);
    return new ValidatedDcpPresentation.PresentationPayload(
        bytes, VerificationConstants.MEDIA_TYPE_VP_LD_JSON, presentation);
  }

  /** Exposed for diagnostics / tests. */
  public static String presentationResponseType() {
    return Constants.MESSAGE_TYPE_PRESENTATION_RESPONSE;
  }
}
