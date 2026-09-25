package eu.xfsc.fc.core.service.oid4vp;

import de.eecc.oid4vc.oid4vp.api.GenerateRequestOptions;
import de.eecc.oid4vc.oid4vp.api.Oid4Vp;
import de.eecc.oid4vc.oid4vp.request.PresentationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class Oid4vpPresentationRequestService {
    private final Oid4Vp oid4Vp;                       // EECC-Bibliothek
    private final ConnectorBindingChallengeService challenges;
    private final Oid4vpBootstrapProperties props;

    public PresentationRequestResult createBootstrapRequest(String connectorDid) {
        var challenge = challenges.issue(connectorDid);   // Einmal-Challenge, an Connector gebunden
        var dcql = DcqlQueries.membership(props.getTrustedMembershipIssuers(),
                props.isRequireCredentialStatus());

        PresentationRequest request = oid4Vp.generatePresentationRequest(
                GenerateRequestOptions.<PresentationRequest>builder(myPresentationDefinition)
                        .redirect(true)
                        .builderSupplier(() -> PresentationRequest.builder().purpose("LOGIN"))
                        .build());
        return oid4Vp.createPresentationRequest(dcql, props.getVerifierUrl(),
                Map.of("connector_did", connectorDid, "challenge_id", challenge.id()));
        // liefert state + request_uri (bzw. openid4vp://…) für die Wallet
    }

    private PresentationRequestResult toResult(
            de.eecc.oid4vc.oid4vp.request.PresentationRequest request,
            String connectorDid,
            String challengeId
    ) {
        return new PresentationRequestResult(
                request.getState(),
                request.getRequestUri(),
                request.getClientId(),
                connectorDid,
                challengeId
        );
    }
}
