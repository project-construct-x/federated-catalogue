package eu.xfsc.fc.core.service.oid4vp;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.eecc.oid4vc.oid4vp.api.Oid4Vp;
import de.eecc.oid4vc.oid4vp.request.PresentationRequest;
import eu.xfsc.fc.core.dao.oid4vp.BootstrapTokenRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static eu.xfsc.fc.core.service.oid4vp.BootstrapTokenService.SCOPE_BIND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

class BootstrapTokenServiceTest {

    @Mock
    private Oid4Vp oid4Vp;

    @Mock
    private BootstrapTokenRepository tokens;

    @Mock
    private ConnectorBindingChallengeService challenges;

    @Mock
    private Oid4vpBootstrapProperties props;

    @Mock
    private BootstrapTokenService bootstrapTokenService;

    public BootstrapTokenServiceTest() {
        MockitoAnnotations.openMocks(this);
        bootstrapTokenService = new BootstrapTokenService(oid4Vp, tokens, challenges, props);
    }

    /**
     * Test for successful token redemption.
     */
    @Test
    void testRedeem_successful() throws ParseException {
        // Arrange
        String state = "test-state";
        String responseCode = "test-response-code";
        String connectorDid = "did:web:test.connector";
        String holderDid = "did:web:test.holder";
        String membershipIssuer = "did:web:test.issuer";
        String challengeId = "test-challenge-id";
        String presentationId = "test-presentation-id";

        PresentationRequest request = Mockito.mock(PresentationRequest.class);
        when(request.getState()).thenReturn(state);
        when(request.getClientId()).thenReturn(holderDid);
        when(request.getRequestId()).thenReturn(presentationId);

        VerifiedMembership membership = new VerifiedMembership(holderDid, membershipIssuer, false, false, presentationId);

        Challenge challenge = new Challenge(challengeId, connectorDid, "test-nonce",
                state,
                Instant.now(),
                Instant.now().plus(Duration.ofHours(1)));

        Instant now = Instant.now();
        Duration tokenTtl = Duration.ofHours(1);
        Instant expiresAt = now.plus(tokenTtl);
        String issuer = "https://catalogue.test/oid4vp";

        when(oid4Vp.findPresentationRequestByResponseCode(responseCode)).thenReturn(Optional.of(request));
        when(challenges.findActiveByState(state)).thenReturn(Optional.of(challenge));
        when(props.getTokenTtl()).thenReturn(tokenTtl);
        when(props.getTokenIssuer()).thenReturn(issuer);
        when(props.getTokenSigningKey()).thenReturn("{\"kty\":\"oct\","
                + "\"k\":\"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY\",\"alg\":\"HS256\"}");

        // Act
        BootstrapTokenResponse response = bootstrapTokenService.redeem(state, responseCode);

        // Assert
        assertEquals("Bearer", response.tokenType());

        SignedJWT signedJWT = SignedJWT.parse(response.accessToken());

        JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
        claims.getClaims().forEach((k, v) -> System.out.println(k + ": " + v));
        //String jti = claims.getJWTID();

        assertEquals(SCOPE_BIND, claims.getStringClaim("scope"));
        assertEquals(connectorDid, claims.getStringClaim("connector_did"));
        assertEquals(challenge.id(), claims.getStringClaim("challenge_id"));
        assertEquals(holderDid, claims.getStringClaim("membership_issuer"));

        assertEquals(false, claims.getBooleanClaim("is_consumer"));
        assertEquals(false, claims.getBooleanClaim("is_provider"));

        assertEquals(presentationId, claims.getStringClaim("presentation_id"));

        assertEquals(props.getTokenIssuer(), claims.getIssuer());
        assertEquals(holderDid, claims.getSubject());
    }

    /**
     * Test for unmatched state and response code.
     */
    @Test
    void testRedeem_stateMismatch() {
        // Arrange
        String state = "test-state";
        String responseCode = "test-response-code";

        PresentationRequest request = Mockito.mock(PresentationRequest.class);
        when(request.getState()).thenReturn("other-state");

        when(oid4Vp.findPresentationRequestByResponseCode(responseCode)).thenReturn(Optional.of(request));

        // Assert
        assertThrows(IllegalArgumentException.class, () -> {
            // Act
            bootstrapTokenService.redeem(state, responseCode);
        });
    }

    /**
     * Test for unknown response code.
     */
    @Test
    void testRedeem_unknownResponseCode() {
        // Arrange
        String state = "test-state";
        String responseCode = "test-response-code";

        when(oid4Vp.findPresentationRequestByResponseCode(responseCode)).thenReturn(Optional.empty());

        // Assert
        assertThrows(IllegalArgumentException.class, () -> {
            // Act
            bootstrapTokenService.redeem(state, responseCode);
        });
    }

    /**
     * Test for challenge resolution failure.
     */
    @Test
    void testRedeem_missingChallenge() {
        // Arrange
        String state = "test-state";
        String responseCode = "test-response-code";

        PresentationRequest request = Mockito.mock(PresentationRequest.class);
        when(request.getState()).thenReturn(state);

        when(oid4Vp.findPresentationRequestByResponseCode(responseCode)).thenReturn(Optional.of(request));
        when(challenges.findActiveByState(state)).thenReturn(Optional.empty());

        // Assert
        assertThrows(IllegalStateException.class, () -> {
            // Act
            bootstrapTokenService.redeem(state, responseCode);
        });
    }
}