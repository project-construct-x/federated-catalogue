package eu.xfsc.fc.core.service.dcp;

import de.eecc.dcp.api.DcpOptions;
import de.eecc.dcp.api.DcpPresentation;
import de.eecc.dcp.message.PresentationQueryMessage;
import de.eecc.dcp.message.PresentationResponseMessage;
import de.eecc.dcp.query.PresentationQueryDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Thin catalogue orchestration for the DCP verifier pull: host transport (SI token, DID→CS,
 * HTTP) plus EECC protocol helpers. Presentation request definitions come from
 * {@link DcpPresentationRequestService}; response shape and holder binding are enforced by
 * {@link DcpPresentation#verifyAndExtractClaims} via {@code requiresSubjectId}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DcpVerifierService {

  private final SelfIssuedIdTokenValidator siTokenValidator;
  private final CredentialServiceDiscovery credentialServiceDiscovery;
  private final DcpPresentationRequestService requestService;
  private final VerifierSiTokenFactory verifierSiTokenFactory;
  private final CredentialServiceClient credentialServiceClient;

  /**
   * Validates the client SI token, pulls presentations from the holder's Credential Service,
   * and validates the response with the stored query definition (including holder binding).
   */
  public PresentationResponseMessage pullPresentations(String authorizationHeader, String purpose) {
    String effectivePurpose =
        (purpose == null || purpose.isBlank()) ? DcpPurposes.POST_ASSETS : purpose.strip();

    ValidatedSelfIssuedIdToken clientToken = siTokenValidator.validate(authorizationHeader);
    String holderDid = clientToken.holderDid();
    String csUrl = credentialServiceDiscovery.resolveCredentialServiceUrl(holderDid);

    PresentationQueryDefinition definition = requestService.requireQueryDefinition(effectivePurpose)
        .requiresSubjectId(holderDid);

    DcpPresentation dcp = DcpPresentation.create(DcpOptions.builder().build());
    PresentationQueryMessage queryMessage = dcp.buildQueryMessage(definition);
    String verifierSi = verifierSiTokenFactory.create(csUrl, clientToken.opaqueToken());

    PresentationResponseMessage response =
        credentialServiceClient.queryPresentations(csUrl, queryMessage, verifierSi);

    // Package enforces structure + requiresSubjectId / requiresIssuer constraints
    dcp.verifyAndExtractClaims(definition, response);

    log.debug("pullPresentations; purpose={}, holderDid={}, presentations={}",
        effectivePurpose, holderDid,
        response.presentation() == null ? 0 : response.presentation().size());
    return response;
  }
}
