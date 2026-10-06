package eu.xfsc.fc.server.controller;

/*-
 * ---license-start
 * fc-service-server
 * ---
 * Copyright (c) 2022 - 2026 Contributors to the Eclipse Foundation
 * ---
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 * ---license-end
 */

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.eecc.oid4vc.oid4vp.VpTokenResponse;
import de.eecc.oid4vc.oid4vp.api.DirectPostHandler;
import de.eecc.oid4vc.oid4vp.api.Oid4Vp;
import de.eecc.oid4vc.oid4vp.request.PresentationRequest;
import de.eecc.oid4vc.oid4vp.request.PresentationRequestDefinition;
import eu.xfsc.fc.core.dao.oid4vp.ConnectorBindingChallengeRepository;
import eu.xfsc.fc.core.dao.oid4vp.ConnectorBindingRepository;
import eu.xfsc.fc.core.pojo.CredentialVerificationResult;
import eu.xfsc.fc.core.pojo.Validator;
import eu.xfsc.fc.core.service.oid4vp.BootstrapTokenService;
import eu.xfsc.fc.core.service.oid4vp.Challenge;
import eu.xfsc.fc.core.service.resolve.DidDocumentResolver;
import eu.xfsc.fc.core.service.verification.VerificationService;
import foundation.identity.did.DIDDocument;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
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
class Oid4vpBootstrapControllerTest {

    private static final String HOLDER = "did:web:wallet.edc1.construct-x.prod-k8s.eecc.de:edc1";
    private static final String ISSUER = "did:web:issuer.int.construct-x.net:issuer";
    private static final String CONNECTOR_A = "did:web:connector-a.construct-x.example";
    private static final String CONNECTOR_B = "did:web:connector-b.construct-x.example";
    private static final String SIGNING_JWK = "{\"kty\":\"oct\","
        + "\"k\":\"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY\",\"alg\":\"HS256\"}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;
    @Autowired
    private ConnectorBindingRepository bindingsRepo;

    @Autowired
    private ConnectorBindingChallengeRepository challengesRepo;

    @MockitoBean
    private Oid4Vp oid4Vp;
    @MockitoBean
    private JwtDecoder jwtDecoder;
    @MockitoBean
    private DidDocumentResolver didDocumentResolver;
    @MockitoBean(name = "verificationServiceImpl")
    private VerificationService verification;

    @BeforeEach
    void cleanDatabaseAndMocks() {
        reset(oid4Vp, jwtDecoder, didDocumentResolver, verification);
        when(jwtDecoder.decode(anyString())).thenThrow(new JwtException("Invalid test JWT"));
        jdbc.getJdbcOperations().execute("DELETE FROM oid4vp_connector_binding");
        jdbc.getJdbcOperations().execute("DELETE FROM oid4vp_bootstrap_token");
        jdbc.getJdbcOperations().execute("DELETE FROM oid4vp_binding_challenge");
    }

    /**
     * Tests the full OID4VP bootstrap flow: request generation, direct post, token redemption and connector binding.
     * @throws Exception
     */
    @Test
    void bootstrapSuccess() throws Exception {
        String token = bootstrapToken(HOLDER, "state-success", "code-success", "vp-token-success");

        mockMvc.perform(post("/api/auth/oid4vp/connectors/{connectorDid}/bind", HOLDER)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isCreated()) // 201 Created
            .andExpect(jsonPath("$.connectorDid").value(HOLDER))
            .andExpect(jsonPath("$.holderDid").value(HOLDER));

        assertTrue(bindingsRepo.findActiveByConnectorDid(HOLDER).isPresent()); // test persistence of binding in database
    }

    /**
     *
     * @throws Exception
     */
    @Test
    void bindWithoutKeycloak() throws Exception {
        String token = bootstrapToken(HOLDER, "state-bind", "code-bind", "vp-token-bind");

        mockMvc.perform(post("/api/auth/oid4vp/connectors/{connectorDid}/bind", HOLDER)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/oid4vp/connectors/{connectorDid}/bind", HOLDER)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + syntacticJwt("{\"typ\":\"JWT\"}",
                    "{\"iss\":\"https://keycloak.test/realms/fc\"}")))
            .andExpect(status().isUnauthorized());
    }

    /**
     * This is a negative test for the direct-post endpoint. It verifies that the application rejects 1 type of invalid membership credentials:
     *
     * 1. a credential from an untrusted issuer
     *
     * In both cases, the response must be a 400 Bad Request, containing an appropriate error message and omitting the response_code.
     * Without a response_code, no token exchange takes place.
     * @throws Exception
     */
    @Test
    void untrustedIssuerRejectDirectPostWithoutResponseCode() throws Exception {
        stubGeneratedRequest("state-untrusted");
        stubDirectPost("state-untrusted", "code-untrusted",
            membershipPresentation("did:web:untrusted.example", HOLDER, true), true);

        mockMvc.perform(post("/api/auth/oid4vp/requests")
                .param("connectorDid", HOLDER));

//        List<Challenge> challenges = challengesRepo.findAll();
//        System.out.println("---------challenges---------------");
//        System.out.println(challenges);
//        System.out.println("----------------------------------");

        mockMvc.perform(post("/api/auth/oid4vp/direct-post")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("vp_token", "vp-token-untrusted")
                .param("state", "state-untrusted"))
            .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.message", startsWith("Signed membership issuer")));

        assertTrue(bindingsRepo.findActiveByConnectorDid(HOLDER).isEmpty());
    }

    /**
     *  * a credential with a status of REVOKED instead of ACTIVE.
     * @throws Exception
     */
    @Test
    void revokedStatusRejectDirectPostWithoutResponseCode() throws Exception {
        stubGeneratedRequest("state-revoked");
        stubDirectPost("state-revoked", "code-revoked", membershipPresentation(ISSUER, HOLDER, true), true);
        when(verification.verifyCredential(any(), eq(true), eq(true), eq(true), eq(false)))
                .thenReturn(verified("REVOKED", HOLDER, ISSUER));

        mockMvc.perform(post("/api/auth/oid4vp/requests")
                        .param("connectorDid", HOLDER))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/oid4vp/direct-post")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("vp_token", "vp-token-revoked")
                        .param("state", "state-revoked"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Membership credential status must be ACTIVE"));
    }

    /**
     * Tests that a connector DID that is not controlled by the holder DID in the membership credential is rejected.
     * @throws Exception
     */
    @Test
    void unauthorizedConnector() throws Exception {
        stubGeneratedRequest("state-unauthorized");
        stubDirectPost("state-unauthorized", "code-unauthorized",
            membershipPresentation(ISSUER, HOLDER, true), true);
        DIDDocument unauthorizedConnectorDocument = didDocument(CONNECTOR_A, "did:web:other-holder.example");
        doReturn(unauthorizedConnectorDocument).when(didDocumentResolver).resolveDidDocument(CONNECTOR_A);

        when(verification.verifyCredential(any(), eq(true), eq(true), eq(true), eq(false)))
                .thenReturn(verified("ACTIVE", HOLDER, ISSUER));

        mockMvc.perform(post("/api/auth/oid4vp/requests")
                .param("connectorDid", CONNECTOR_A))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/oid4vp/direct-post")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("vp_token", "vp-token-unauthorized")
                .param("state", "state-unauthorized"))
            .andExpect(status().isForbidden());

        assertTrue(bindingsRepo.findActiveByConnectorDid(CONNECTOR_A).isEmpty());
    }

    @Test
    void replayVpTokenResponseCodeAndBootstrapToken() throws Exception {
        stubGeneratedRequest("state-replay-vp");
        AtomicBoolean firstVpTokenUse = new AtomicBoolean(true);
        stubDirectPost("state-replay-vp", "code-replay-vp", membershipPresentation(ISSUER, HOLDER, true),
            firstVpTokenUse);
        when(verification.verifyCredential(any(), eq(true), eq(true), eq(true), eq(false)))
            .thenReturn(verified("ACTIVE", HOLDER, ISSUER));

        mockMvc.perform(post("/api/auth/oid4vp/requests")
                .param("connectorDid", HOLDER))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/oid4vp/direct-post")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("vp_token", "vp-token-replay")
                .param("state", "state-replay-vp"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/oid4vp/direct-post")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("vp_token", "vp-token-replay")
                .param("state", "state-replay-vp"))
            .andExpect(status().isBadRequest());

        String replayCodeToken = bootstrapToken(HOLDER, "state-replay-code", "code-replay", "vp-token-code");
        assertTrue(replayCodeToken.startsWith("ey"));
        mockMvc.perform(post("/api/auth/oid4vp/token")
                .param("state", "state-replay-code")
                .param("response_code", "code-replay"))
            .andExpect(status().isBadRequest());

        String bootstrapToken = bootstrapToken(HOLDER, "state-replay-bootstrap", "code-bootstrap",
            "vp-token-bootstrap");
        mockMvc.perform(post("/api/auth/oid4vp/connectors/{connectorDid}/bind", HOLDER)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bootstrapToken))
            .andExpect(status().isCreated());
        String firstBindingId = bindingsRepo.findActiveByConnectorDid(HOLDER).orElseThrow().id();

        mockMvc.perform(post("/api/auth/oid4vp/connectors/{connectorDid}/bind", HOLDER)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bootstrapToken))
            .andExpect(status().isBadRequest());
        assertEquals(firstBindingId, bindingsRepo.findActiveByConnectorDid(HOLDER).orElseThrow().id());
    }

    @Test
    void expiredToken() throws Exception {
        String expired = signedBootstrapToken(HOLDER, HOLDER, "expired-challenge", Instant.now().minusSeconds(60));

        mockMvc.perform(post("/api/auth/oid4vp/connectors/{connectorDid}/bind", HOLDER)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
            .andExpect(status().isUnauthorized());

        assertTrue(bindingsRepo.findActiveByConnectorDid(HOLDER).isEmpty());
    }

    @Test
    void expiredChallenge() throws Exception {
        stubGeneratedRequest("state-expired-challenge");
        stubDirectPost("state-expired-challenge", "code-expired-challenge",
            membershipPresentation(ISSUER, HOLDER, true), true);

        mockMvc.perform(post("/api/auth/oid4vp/requests")
                .param("connectorDid", HOLDER))
            .andExpect(status().isOk());
        expireChallenge("state-expired-challenge");

        mockMvc.perform(post("/api/auth/oid4vp/direct-post")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("vp_token", "vp-token-expired-challenge")
                .param("state", "state-expired-challenge"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("No connector binding challenge found for OID4VP state"));

        assertTrue(bindingsRepo.findActiveByConnectorDid(HOLDER).isEmpty());
    }

    @Test
    void bootstrapTokenRejectedOnMachineEndpoints() throws Exception {
        String token = signedBootstrapToken(HOLDER, HOLDER, "machine-challenge", Instant.now().plusSeconds(300));

        mockMvc.perform(get("/assets").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/participants").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/dcp/presentations").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void connectorMismatch() throws Exception {
        stubConnectorDocument(CONNECTOR_A, HOLDER);
        String token = bootstrapToken(CONNECTOR_A, "state-mismatch", "code-mismatch", "vp-token-mismatch");

        mockMvc.perform(post("/api/auth/oid4vp/connectors/{connectorDid}/bind", CONNECTOR_B)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isForbidden());

        assertTrue(bindingsRepo.findActiveByConnectorDid(CONNECTOR_B).isEmpty());
    }

    /**
     * Simulates the full OID4VP bootstrap flow and returns the resulting bootstrap token.
     * Everything external (wallet, verifier, credential verification) is stubbed.
     * @param connectorDid
     * @param state
     * @param code
     * @param vpToken
     * @return
     * @throws Exception
     */
    private String bootstrapToken(String connectorDid, String state, String code, String vpToken) throws Exception {
        stubGeneratedRequest(state);
        stubDirectPost(state, code, membershipPresentation(ISSUER, HOLDER, true), true);
        when(verification.verifyCredential(any(), eq(true), eq(true), eq(true), eq(false)))
                .thenReturn(verified("ACTIVE", HOLDER, ISSUER));
        stubTokenRedemption(state, code);

        mockMvc.perform(post("/api/auth/oid4vp/requests")
                        .param("connectorDid", connectorDid))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/oid4vp/direct-post")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("vp_token", vpToken)
                        .param("state", state))
                .andExpect(status().isOk());
        String response = mockMvc.perform(post("/api/auth/oid4vp/token")
                        .param("state", state)
                        .param("response_code", code))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("accessToken").asText();
    }

    private void stubGeneratedRequest(String state) {
        when(oid4Vp.generatePresentationRequest(any(PresentationRequestDefinition.class)))
            .thenReturn(PresentationRequest.builder()
            .state(state)
            .clientId("did:web:catalogue.test")
            .requestUri("openid4vp://request/" + state)
            .requestId("request-" + state)
            .build());
    }

    private void stubDirectPost(String state, String code, JsonNode presentation, boolean verified) {
        stubDirectPost(state, code, presentation, new AtomicBoolean(verified));
    }

    private void stubDirectPost(String state, String code, JsonNode presentation, AtomicBoolean firstUse) {
        when(oid4Vp.processDirectPost(anyString(), eq(state), any())).thenAnswer(invocation -> {
            if (!firstUse.getAndSet(false)) {
                throw new IllegalArgumentException("vp_token replay detected");
            }
            DirectPostHandler<PresentationRequest> handler = invocation.getArgument(2);
            PresentationRequest request = PresentationRequest.builder()
                .state(state)
                .clientId(HOLDER)
                .requestId("request-" + state)
                .build();
            handler.onVerified(request, presentation);
            return VpTokenResponse.DirectPostResponse.redirect("https://wallet.example/callback?response_code="
                + code);
        });
    }

    /**
     * Stubs the OID4VP library to return a presentation request when the response code is redeemed.
     * @param state
     * @param code
     */
    private void stubTokenRedemption(String state, String code) {
        PresentationRequest request = PresentationRequest.builder()
            .state(state)
            .clientId(HOLDER)
            .requestId("request-" + state)
            .responseCode(code)
            .vpToken(vpJwt("vp-" + state))
            .build();
        when(oid4Vp.findPresentationRequestByResponseCode(code))
            .thenReturn(Optional.of(request), Optional.empty());
    }

    private JsonNode membershipPresentation(String issuer, String holder, boolean provider) throws Exception {
        ObjectNode credential = (ObjectNode) objectMapper.readTree(Files.readString(membershipFixture()));
        credential.put("issuer", issuer);
        ObjectNode subject = (ObjectNode) credential.get("credentialSubject");
        subject.put("id", holder);
        subject.put("isConsumer", false);
        subject.put("isProvider", provider);

        ObjectNode presentation = objectMapper.createObjectNode();
        presentation.put("id", "vp-fixture");
        presentation.putArray("type").add("VerifiablePresentation");
        presentation.put("holder", holder);
        presentation.putArray("verifiableCredential").add(credential);
        return presentation;
    }

    private Path membershipFixture() {
        Path current = Path.of(System.getProperty("user.dir"));
        Path fromModule = current.resolve("../examples/membership-credential-v1.jsonld")
            .normalize();
        if (Files.exists(fromModule)) {
            return fromModule;
        }
        return current.resolve("examples/membership-credential-v1.jsonld").normalize();
    }

    private void expireChallenge(String state) {
        jdbc.update("UPDATE oid4vp_binding_challenge SET expires_at = :expiresAt WHERE state = :state",
            Map.of("expiresAt", Timestamp.from(Instant.now().minusSeconds(60)), "state", state));
    }

    private CredentialVerificationResult verified(String status, String holder, String issuer) {
        return new CredentialVerificationResult(Instant.now(), status, issuer, Instant.now(), holder,
            List.of(), List.of(new Validator(holder + "#key", null, null),
                new Validator(issuer + "#key", null, null)), null, null);
    }

    private void stubConnectorDocument(String connectorDid, String holderDid) {
        DIDDocument connectorDocument = didDocument(connectorDid, holderDid);
        doReturn(connectorDocument).when(didDocumentResolver).resolveDidDocument(connectorDid);
    }

    private DIDDocument didDocument(String id, String controller) {
        return DIDDocument.fromJson("{\"id\":\"" + id + "\",\"controller\":\""
            + controller + "\"}");
    }

    private String vpJwt(String jwtId) {
        String vcJwt = syntacticJwt("{\"alg\":\"RS256\"}", "{\"iss\":\"" + ISSUER + "\"}");
        String vcArray;
        try {
            vcArray = objectMapper.writeValueAsString(List.of(vcJwt));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return syntacticJwt("{\"alg\":\"RS256\"}", "{\"iss\":\"" + HOLDER + "\",\"sub\":\""
            + HOLDER + "\",\"jti\":\"" + jwtId + "\",\"vp\":{\"verifiableCredential\":"
            + vcArray + "}}");
    }

    private String syntacticJwt(String header, String payload) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
            + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".c2ln";
    }

    private String signedBootstrapToken(String holder, String connectorDid, String challengeId, Instant expiresAt)
        throws Exception {
        Instant now = Instant.now();
        String jti = UUID.randomUUID().toString();
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

