package eu.xfsc.fc.core.service.dcp;

/**
 * Claims from a validated DCP Self-Issued ID Token.
 *
 * @param holderDid participant DID ({@code sub} / {@code iss})
 * @param audience token {@code aud} (may be null)
 * @param jti unique token id
 * @param opaqueToken optional opaque {@code token} claim to forward to the Credential Service
 * @param rawJwt compact serialization of the validated token
 */
public record ValidatedSelfIssuedIdToken(
    String holderDid,
    String audience,
    String jti,
    String opaqueToken,
    String rawJwt) {
}
