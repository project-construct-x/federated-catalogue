package eu.xfsc.fc.core.service.dcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jwt.SignedJWT;
import de.eecc.dcp.vp.PresentationParser;
import eu.xfsc.fc.core.config.DcpProperties;
import eu.xfsc.fc.core.pojo.ContentAccessorDirect;
import eu.xfsc.fc.core.security.DcpIdentity;
import eu.xfsc.fc.core.service.verification.VerificationConstants;
import eu.xfsc.fc.core.service.verification.VerificationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

/** Strict authentication boundary for asset writes; ingest verification toggles do not weaken it. */
@Service
@RequiredArgsConstructor
public class DcpMachineAuthenticationService {
  private final DcpVerifierService verifier;
  private final VerificationService verification;
  private final DcpProperties properties;

  /**
   * Requires one unambiguous membership presentation, a configured issuer and audience,
   * cryptographic VC/VP verification and a membership subject equal to the SI-authenticated holder.
   * Delegated actors and multiple credentials are deliberately not supported by this first policy.
   */
  public DcpIdentity authenticateAssetWrite(String authorization) {
    String issuer = properties.getPostAssets().getRequiredIssuer();
    if (issuer == null || issuer.isBlank() || properties.getAudience() == null
        || properties.getAudience().isBlank()) {
      throw new AuthenticationServiceException("DCP audience and membership issuer must be configured");
    }
    try {
      var pulled = verifier.pullForAuthentication(authorization, DcpPurposes.POST_ASSETS);
      var presentations = pulled.response().presentation();
      if (presentations == null || presentations.size() != 1) {
        throw new IllegalArgumentException("Exactly one membership presentation required");
      }
      JsonNode presentation = presentations.getFirst();
      if (!presentation.isTextual()
          || !pulled.holderDid().equals(SignedJWT.parse(presentation.asText()).getJWTClaimsSet().getIssuer())) {
        throw new IllegalArgumentException("Signed holder presentation required");
      }
      JsonNode root = PresentationParser.presentationRoot(presentation);
      if (root != null && root.has("vp")) {
        root = root.get("vp");
      }
      JsonNode credentials = root == null ? null : root.get("verifiableCredential");
      if (credentials == null || !credentials.isArray() || credentials.size() != 1) {
        throw new IllegalArgumentException("Exactly one membership credential required");
      }
      JsonNode credential = credentials.get(0);
      String membershipJwt = credential.isTextual() ? credential.asText()
          : PresentationParser.extractEnvelopedJwtFromCredential(credential);
      if (membershipJwt == null
          || !issuer.equals(SignedJWT.parse(membershipJwt).getJWTClaimsSet().getIssuer())) {
        throw new IllegalArgumentException("Signed membership issuer must match trust policy");
      }
      if (!"MembershipCredential".equals(PresentationParser.extractCredentialType(
          presentation, List.of("MembershipCredential")))
          || !issuer.equals(PresentationParser.extractIssuer(presentation))) {
        throw new IllegalArgumentException("Membership policy not satisfied");
      }
      var subjects = PresentationParser.collectCredentialSubjects(presentation);
      if (subjects.size() != 1 || !subjects.getFirst().path("id").isTextual()) {
        throw new IllegalArgumentException("Unambiguous membership subject required");
      }
      String participant = subjects.getFirst().path("id").asText();
      if (!participant.startsWith("did:") || !participant.equals(pulled.holderDid())) {
        throw new IllegalArgumentException("Membership subject must match authenticated holder");
      }
      String content = presentation.isTextual() ? presentation.asText() : presentation.toString();
      String mediaType = presentation.isTextual()
          ? VerificationConstants.MEDIA_TYPE_VP_JWT : VerificationConstants.MEDIA_TYPE_VP_LD_JSON;
      // Never trust only the EECC query/claim extraction checks, or the lab signature toggles.
      var verified = verification.verifyCredential(
          new ContentAccessorDirect(content, mediaType), true, true, true, false);
      if (verified == null || verified.getValidators() == null || verified.getValidators().size() < 2
          || !participant.equals(verified.getId()) || !issuer.equals(verified.getIssuer())) {
        throw new IllegalArgumentException("Verified membership and both signatures required");
      }
      return new DcpIdentity(participant, pulled.holderDid());
    } catch (RuntimeException | java.text.ParseException ex) {
      // Upstream exceptions may contain raw tokens or presentations. Do not expose or log them.
      throw new BadCredentialsException("DCP membership authentication failed");
    }
  }
}
