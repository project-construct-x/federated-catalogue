package eu.xfsc.fc.server.service;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import eu.xfsc.fc.core.dao.oid4vp.BootstrapTokenRepository;
import eu.xfsc.fc.core.service.oid4vp.BootstrapPrincipal;
import eu.xfsc.fc.core.service.oid4vp.BootstrapTokenService;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureEmbeddedDatabase(provider = DatabaseProvider.ZONKY)
@TestPropertySource(properties = {
        "federated-catalogue.oid4vp.enabled=true",
        "federated-catalogue.oid4vp.token-issuer=https://catalogue.test/oid4vp",
        "federated-catalogue.oid4vp.token-ttl=5m",
        "federated-catalogue.oid4vp.challenge-ttl=10m",
        "federated-catalogue.oid4vp.trusted-membership-issuers[0]=did:web:issuer.int.construct-x.net:issuer",
        "federated-catalogue.oid4vp.token-signing-key={\"kty\":\"oct\","
                + "\"k\":\"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY\",\"alg\":\"HS256\"}"
})
@Transactional // Rollback der DB-Operationen nach jedem Test
public class BootstrapTokenServiceSpringBootTest {

    @Autowired
    private BootstrapTokenService bootstrapTokenService;

    @Autowired
    private BootstrapTokenRepository tokens;

    private static final String HOLDER = "did:web:wallet.edc1.construct-x.prod-k8s.eecc.de:edc1";
    private static final String ISSUER = "did:web:issuer.int.construct-x.net:issuer";

    private static final String SCOPE_BIND = "connector:bind";
    private static final String TOKEN_TYPE = "oid4vp_bootstrap";
    private static final String membershipIssuer =  "did:web:issuer.int.construct-x.net:issuer";
    private static final String SIGNING_JWK = "{\"kty\":\"oct\","
            + "\"k\":\"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY\",\"alg\":\"HS256\"}";

    private String validJwt;
    private String connectorDid;
    private String jti;

    @BeforeEach
    void setUp() throws Exception {
        // Vorbereiten der Testdaten
        connectorDid = "did:web:example.com:connector:123";
        jti = UUID.randomUUID().toString();

        validJwt = signedBootstrapToken(HOLDER, connectorDid, "test-challenge", Instant.now().plusSeconds(6000),
                jti);

        // Bereite die Datenbank vor: Speichere ein nicht eingelöstes Token
        BootstrapTokenRepository.BootstrapTokenRecord tokenEntity = new BootstrapTokenRepository.BootstrapTokenRecord(jti, HOLDER, connectorDid,
                ISSUER, membershipIssuer, SCOPE_BIND, BootstrapTokenService.TOKEN_TYPE, Instant.now(), Instant.now().plus(10, ChronoUnit.MINUTES), null, null);

        tokens.save(tokenEntity);
    }

    // --- TESTS FOR "consume" ---

    @Test
    void consume_shouldSucceedAndMarkRedeemed_whenTokenIsValid() {
        // Act
        BootstrapPrincipal principal = bootstrapTokenService.consume(validJwt, connectorDid);

        // Assert
        assertThat(principal).isNotNull();
        assertThat(principal.jti()).isEqualTo(jti);
        assertThat(principal.connectorDid()).isEqualTo(connectorDid);

        // Überprüfe, ob die Datenbank aktualisiert wurde (redeemed_at IS NOT NULL)
        BootstrapTokenRepository.BootstrapTokenRecord updatedToken = tokens.findByJti(jti).orElseThrow();
        assertThat(updatedToken.redeemedAt()).isNotNull();
    }

    @Test
    void consume_shouldThrow_whenConnectorDidMismatch() {
        // Arrange
        String wrongConnectorDid = "did:web:example.com:connector:999";

        // Act & Assert
        assertThatThrownBy(() -> bootstrapTokenService.consume(validJwt, wrongConnectorDid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bootstrap token connector mismatch");
    }

    @Test
    void consume_shouldThrow_whenTokenIsAlreadyRedeemed() throws Exception {
        // Arrange: Token in der DB als eingelöst markieren
        tokens.markRedeemedIfActive(jti, Instant.now().minus(1, ChronoUnit.MINUTES));

        // Act & Assert
        assertThatThrownBy(() -> bootstrapTokenService.consume(validJwt, connectorDid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bootstrap token is no longer active");
    }

    @Test
    void consume_shouldThrow_whenTokenIsRevoked() throws Exception {
        // Arrange: Token in der DB als widerrufen markieren
        BootstrapTokenRepository.BootstrapTokenRecord tokenEntity = tokens.findByJti(jti).orElseThrow();
        String jti_new = UUID.randomUUID().toString();

        String validJwt_new = signedBootstrapToken(HOLDER, connectorDid, "test-challenge", Instant.now().plusSeconds(6000),
                jti_new);
        BootstrapTokenRepository.BootstrapTokenRecord modifiedTokenEntity = new BootstrapTokenRepository.BootstrapTokenRecord(
                jti_new,
                tokenEntity.holderDid(),
                tokenEntity.connectorDid(),
                tokenEntity.membershipIssuer(),
                tokenEntity.presentationId(),
                tokenEntity.scope(),
                tokenEntity.tokenType(),
                tokenEntity.createdAt(),
                tokenEntity.expiresAt(),
                tokenEntity.redeemedAt(),
                Instant.now().minus(1, ChronoUnit.MINUTES)      // neuer revokedAt-Wert
        );
        tokens.save(modifiedTokenEntity);

        // Act & Assert
        assertThatThrownBy(() -> bootstrapTokenService.consume(validJwt_new, connectorDid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bootstrap token is no longer active");
    }

    // --- TESTS FOR "verify" ---

    @Test
    void verify_shouldReturnPrincipal_whenJwtIsValid() {
        // Act
        BootstrapPrincipal principal = bootstrapTokenService.verify(validJwt);

        // Assert
        assertThat(principal).isNotNull();
        assertThat(principal.jti()).isEqualTo(jti);
        assertThat(principal.connectorDid()).isEqualTo(connectorDid);
    }

    @Test
    void verify_shouldThrow_whenJwtIsExpiredOrInvalid() throws Exception {
        // Arrange: Ein abgelaufenes oder syntaktisch falsches JWT
        String expiredJwt = signedBootstrapToken(HOLDER, connectorDid, "test-challenge", Instant.now()
                .minusSeconds(3600), jti); // Abgelaufen vor 1 Stunde
        // Act & Assert
        // Annahme: verify wirft eine JwtException oder ähnliches.
        // Passe den Exception-Typ an deine Implementierung an.
        assertThatThrownBy(() -> bootstrapTokenService.verify(expiredJwt))
                .isInstanceOf(RuntimeException.class); // Z.B. ExpiredJwtException
    }

    @Test
    void verify_shouldThrow_whenJwtIsMalformed() {
        // Act & Assert
        assertThatThrownBy(() -> bootstrapTokenService.verify("this-is-not-a-jwt"))
                .isInstanceOf(RuntimeException.class); // Z.B. MalformedJwtException
    }

    // --- HELPER METHODS ---

    private String signedBootstrapToken(String holder, String connectorDid, String challengeId, Instant expiresAt, String jti)
            throws Exception {
        Instant now = Instant.now();
        if (jti == null) {
            jti = UUID.randomUUID().toString();
        }
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://catalogue.test/oid4vp")
                .subject(holder)
                .audience("https://catalogue.test/oid4vp")
                .jwtID(jti)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .claim("scope", BootstrapTokenService.SCOPE_BIND)
                .claim("connector_did", connectorDid)
                .claim("challenge_id", challengeId)
                .claim("membership_issuer", ISSUER)
                .claim("is_consumer", false)
                .claim("is_provider", true)
                .claim("presentation_id", "presentation-" + challengeId)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(new JOSEObjectType(BootstrapTokenService.TOKEN_TYPE)).build(), claims);
        jwt.sign(new MACSigner(OctetSequenceKey.parse(SIGNING_JWK)));
        return jwt.serialize();
    }
}
