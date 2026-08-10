package eu.xfsc.fc.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.eecc.dcp.message.PresentationResponseMessage;
import eu.xfsc.fc.core.service.dcp.DcpVerifierService;
import eu.xfsc.fc.server.generated.controller.DcpApiDelegate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * OpenAPI delegate for {@code POST /dcp/presentations}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DcpPresentationService implements DcpApiDelegate {

  private final DcpVerifierService dcpVerifierService;
  private final ObjectMapper objectMapper;

  @Override
  public ResponseEntity<eu.xfsc.fc.api.generated.model.PresentationResponseMessage> pullDcpPresentations(
      String authorization,
      String purpose) {
    log.debug("pullDcpPresentations.enter; purpose={}", purpose);
    PresentationResponseMessage response =
        dcpVerifierService.pullPresentations(authorization, purpose);
    eu.xfsc.fc.api.generated.model.PresentationResponseMessage body =
        objectMapper.convertValue(
            response, eu.xfsc.fc.api.generated.model.PresentationResponseMessage.class);
    log.debug("pullDcpPresentations.exit; presentationCount={}",
        body.getPresentation() == null ? 0 : body.getPresentation().size());
    return ResponseEntity.ok(body);
  }
}
