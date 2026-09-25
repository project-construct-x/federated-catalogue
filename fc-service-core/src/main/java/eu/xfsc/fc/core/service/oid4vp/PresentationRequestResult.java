package eu.xfsc.fc.core.service.oid4vp;

public record PresentationRequestResult(
        String state,
        String requestUri,
        String qrCodeUri,
        String challengeId,
        String connectorDid
) {
}
