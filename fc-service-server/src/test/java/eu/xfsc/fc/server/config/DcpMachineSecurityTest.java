package eu.xfsc.fc.server.config;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.eecc.dcp.message.PresentationResponseMessage;
import de.eecc.dcp.query.template.constructx.MembershipQueryDefinition;
import eu.xfsc.fc.core.config.DcpProperties;
import eu.xfsc.fc.core.pojo.CredentialVerificationResult;
import eu.xfsc.fc.core.pojo.Validator;
import eu.xfsc.fc.core.security.DcpParticipantAccess;
import eu.xfsc.fc.core.service.dcp.*;
import eu.xfsc.fc.core.service.resolve.DidDocumentResolver;
import eu.xfsc.fc.core.service.verification.VerificationService;
import foundation.identity.did.DIDDocument;
import foundation.identity.did.VerificationMethod;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Real filter, SI signature/replay validation, DCP pull orchestration and membership policy.
 * External DID/CS calls, stored policy lookup and VC/VP cryptography are isolated. */
@SpringJUnitWebConfig(DcpMachineSecurityTest.Config.class)
@TestPropertySource(properties = "keycloak.resource=federated-catalogue")
class DcpMachineSecurityTest {
  private static final String HOLDER = "did:web:holder.example";
  private static final String ISSUER = "did:web:issuer.example";
  private static final String AUDIENCE = "https://catalogue.example";
  private static final String KID = HOLDER + "#key";
  @Autowired WebApplicationContext context;
  @Autowired DcpProperties properties;
  @MockitoBean DidDocumentResolver resolver;
  @Autowired CredentialServiceDiscovery discovery;
  @Autowired CredentialServiceClient client;
  @Autowired DcpPresentationRequestService definitions;
  @Autowired VerifierSiTokenFactory tokens;
  @Autowired VerificationService verification;
  @Autowired JwtDecoder decoder;
  @Autowired DcpReplayStore replay;
  MockMvc mvc;
  OctetKeyPair key;

  @BeforeEach void setup() throws Exception {
    reset(resolver, discovery, client, definitions, tokens, verification, decoder, replay);
    var consumed = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
    when(replay.consume(anyString(), anyString(), any()))
        .thenAnswer(call -> consumed.add(call.getArgument(0) + "|" + call.getArgument(1)));
    properties.setAudience(AUDIENCE);
    properties.getPostAssets().setRequiredIssuer(ISSUER);
    key = new OctetKeyPairGenerator(Curve.Ed25519).keyID(KID).generate();
    var method = mock(VerificationMethod.class);
    when(method.getId()).thenReturn(URI.create(KID));
    when(method.getPublicKeyJwk()).thenReturn(key.toPublicJWK().toJSONObject());
    var document = mock(DIDDocument.class);
    when(document.getCapabilityInvocationVerificationMethodsDereferenced()).thenReturn(List.of(method));
    when(resolver.resolveDidDocument(HOLDER)).thenReturn(document);
    when(discovery.resolveCredentialServiceUrl(HOLDER)).thenReturn("https://holder.example/cs");
    when(definitions.requireQueryDefinition(DcpPurposes.POST_ASSETS)).thenReturn(MembershipQueryDefinition.INSTANCE);
    when(tokens.create(any(), any())).thenReturn("verifier-si");
    when(client.queryPresentations(any(), any(), any())).thenReturn(presentation());
    when(verification.verifyCredential(any(), eq(true), eq(true), eq(true), eq(false)))
        .thenReturn(new CredentialVerificationResult(Instant.now(), "ACTIVE", ISSUER, Instant.now(), HOLDER,
            List.of(), List.of(new Validator(HOLDER + "#key", null, null),
                new Validator(ISSUER + "#key", null, null)), null, null));
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @ParameterizedTest
  @CsvSource({
      "GET,/assets",
      "POST,/assets",
      "POST,/assets/validate",
      "GET,/assets/example",
      "PUT,/assets/example",
      "GET,/assets/example/versions",
      "POST,/assets/example/versions/example/revoke",
      "POST,/assets/example/human-readable",
      "PUT,/assets/example/human-readable",
      "GET,/assets/example/human-readable",
      "GET,/assets/example/machine-readable",
      "GET,/assets/example/validations",
      "POST,/assets/example/compliance-check",
      "GET,/assets/example/compliance-checks",
      "POST,/assets/example/provenance",
      "GET,/assets/example/provenance",
      "POST,/assets/example/provenance/verify",
      "GET,/assets/example/provenance/example",
      "POST,/assets/example/provenance/example/verify",
      "DELETE,/assets/example",
      "DELETE,/assets/by-id/example",
      "POST,/assets/example/revoke",
      "GET,/validations/example",
      "GET,/query",
      "POST,/query",
      "GET,/query/info",
      "POST,/query/search",
      "GET,/trust-frameworks",
      "GET,/verification",
      "POST,/verification",
      "GET,/participants",
      "POST,/participants",
      "GET,/participants/example",
      "PUT,/participants/example",
      "DELETE,/participants/example"})
  void everyMachineOperationAuthenticatesIncomingDcp(String method, String path) throws Exception {
    var verb = HttpMethod.valueOf(method);
    String proof = token(Instant.now().plusSeconds(300));
    mvc.perform(request(verb, path).header("Authorization", "Bearer " + proof)
        .contentType("application/json").content("{\"participant_id\":\"untrusted\"}"))
        .andExpect(status().isOk()).andExpect(header().string("X-Participant", HOLDER))
        .andExpect(content().string("{\"participant_id\":\"untrusted\"}"));
    verify(client).queryPresentations(eq("https://holder.example/cs"), any(), eq("verifier-si"));
    verify(verification).verifyCredential(any(), eq(true), eq(true), eq(true), eq(false));
    assertNull(SecurityContextHolder.getContext().getAuthentication());
    mvc.perform(request(verb, path)).andExpect(status().isUnauthorized());
    mvc.perform(request(verb, path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN_ALL")))
        .header("Authorization", "Bearer keycloak")).andExpect(status().isUnauthorized());
    mvc.perform(request(verb, path).with(jwt().jwt(j -> j.claim("purpose", "connector-bootstrap")))
        .header("Authorization", "Bearer bootstrap")).andExpect(status().isUnauthorized());
    // No second membership pull: all three rejected requests failed before CS access.
    verifyNoMoreInteractions(client, verification);
    verifyNoInteractions(decoder);
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test void rejectsReplayAcrossDifferentEndpointsAndExpiredOrForgedProofs() throws Exception {
    String proof = token(Instant.now().plusSeconds(300));
    mvc.perform(get("/query/info").header("Authorization", "Bearer " + proof)).andExpect(status().isOk());
    mvc.perform(delete("/assets/example").header("Authorization", "Bearer " + proof))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/participants").header("Authorization", "Bearer " + token(Instant.now().minusSeconds(300))))
        .andExpect(status().isUnauthorized());
    key = new OctetKeyPairGenerator(Curve.Ed25519).keyID(KID).generate();
    mvc.perform(post("/assets/example/compliance-check").header("Authorization", "Bearer " + token(Instant.now().plusSeconds(300))))
        .andExpect(status().isUnauthorized());
    verify(client, times(1)).queryPresentations(any(), any(), any());
  }

  @Test void sessionsCannotReplaceFreshProofAndOwnershipStillApplies() throws Exception {
    var session = new MockHttpSession();
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(new eu.xfsc.fc.core.security.DcpAuthenticationToken(
            new eu.xfsc.fc.core.security.DcpIdentity(HOLDER, HOLDER))));
    mvc.perform(get("/participants").session(session)).andExpect(status().isUnauthorized());
    mvc.perform(get("/participants/did:web:other.example").header("Authorization", "Bearer " + token(Instant.now().plusSeconds(300))))
        .andExpect(status().isForbidden());
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test void failedMembershipVerificationNeverReachesHandler() throws Exception {
    when(verification.verifyCredential(any(), eq(true), eq(true), eq(true), eq(false)))
        .thenThrow(new IllegalArgumentException("secret-credential"));
    mvc.perform(post("/query/search").header("Authorization", "Bearer " + token(Instant.now().plusSeconds(300))))
        .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("X-Participant"));
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test void missingTrustConfigurationFailsClosed() throws Exception {
    properties.getPostAssets().setRequiredIssuer("");
    mvc.perform(get("/query/info").header("Authorization", "Bearer " + token(Instant.now().plusSeconds(300))))
        .andExpect(status().isServiceUnavailable());
    verifyNoInteractions(client, verification, resolver);
  }

  @Test void replayStoreFailureCannotAuthorizeAnOperation() throws Exception {
    when(replay.consume(anyString(), anyString(), any()))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"));
    mvc.perform(post("/query/search").header("Authorization", "Bearer " + token(Instant.now().plusSeconds(300))))
        .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("X-Participant"));
    verifyNoInteractions(client, verification);
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test void expiredPresentationIsRejectedAndItsSiTokenRemainsConsumed() throws Exception {
    var original = presentation();
    var expired = new JWTClaimsSet.Builder(SignedJWT.parse(original.presentation().getFirst().asText()).getJWTClaimsSet())
        .expirationTime(Date.from(Instant.now().minusSeconds(1))).build();
    var mapper = new ObjectMapper();
    var response = new PresentationResponseMessage(original.context(), original.type(),
        List.of(mapper.getNodeFactory().textNode(fixtureJwt(mapper.writeValueAsString(expired.toJSONObject())))), null);
    when(client.queryPresentations(any(), any(), any())).thenReturn(response);
    String proof = token(Instant.now().plusSeconds(300));
    mvc.perform(get("/query/info").header("Authorization", "Bearer " + proof))
        .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("X-Participant"));
    when(client.queryPresentations(any(), any(), any())).thenReturn(original);
    mvc.perform(get("/participants").header("Authorization", "Bearer " + proof)).andExpect(status().isUnauthorized());
    verify(client, times(1)).queryPresentations(any(), any(), any());
  }

  @ParameterizedTest
  @CsvSource({"POST,/query/search", "GET,/trust-frameworks", "GET,/validations/example", "POST,/verification"})
  void additionalSurfacesAuditVerifiedIdentityWithoutPayloads(String method, String path) throws Exception {
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
        "eu.xfsc.fc.server.audit.DcpOperationAudit");
    var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    var previousLevel = logger.getLevel();
    logger.setLevel(ch.qos.logback.classic.Level.INFO);
    appender.start();
    logger.addAppender(appender);
    try {
      mvc.perform(request(HttpMethod.valueOf(method), path)
          .header("Authorization", "Bearer " + token(Instant.now().plusSeconds(300)))
          .content("secret-payload").param("query", "secret-query"))
          .andExpect(status().isOk());
      assertEquals(1, appender.list.size());
      String message = appender.list.getFirst().getFormattedMessage();
      var audit = new ObjectMapper().readTree(message);
      assertEquals(HOLDER, audit.get("participantDid").asText());
      assertEquals(HOLDER, audit.get("actorDid").asText());
      assertEquals("DCP", audit.get("authenticationMethod").asText());
      assertEquals("SUCCESS", audit.get("outcome").asText());
      assertEquals(path, audit.get("resource").asText());
      assertFalse(message.contains("secret"));
      assertFalse(message.contains("Bearer"));
      assertNull(SecurityContextHolder.getContext().getAuthentication());
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previousLevel);
      appender.stop();
    }
  }

  @Test void adminProtocolPublicAndUnassignedOperationsDoNotUseMachineAuthentication() throws Exception {
    mvc.perform(get("/admin/me").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN_ALL"))))
        .andExpect(status().isOk());
    mvc.perform(get("/participants/example/users").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN_ALL"))))
        .andExpect(status().isOk());
    mvc.perform(get("/api/docs")).andExpect(status().isOk());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mvc.perform(options("/query")).andExpect(status().isOk());
    // Probe only: the real protocol controller validates its own SI proof.
    mvc.perform(post("/dcp/presentations")).andExpect(status().isOk());
    mvc.perform(post("/api/auth/oid4vp/bootstrap")).andExpect(status().is4xxClientError());
    mvc.perform(patch("/participants/example")).andExpect(status().is4xxClientError());
    mvc.perform(delete("/query")).andExpect(status().is4xxClientError());
    mvc.perform(post("/query/unknown")).andExpect(status().is4xxClientError());
    verifyNoInteractions(client, verification, resolver);
  }

  private String token(Instant expires) throws Exception {
    var claims = new JWTClaimsSet.Builder().issuer(HOLDER).subject(HOLDER).audience(AUDIENCE)
        .jwtID(UUID.randomUUID().toString()).expirationTime(Date.from(expires)).build();
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(KID).build(), claims);
    jwt.sign(new Ed25519Signer(key));
    return jwt.serialize();
  }

  private PresentationResponseMessage presentation() throws Exception {
    var mapper = new ObjectMapper();
    String vc = fixtureJwt("""
        {"iss":"%s","vc":{"type":["VerifiableCredential","MembershipCredential"],
        "issuer":"%s","credentialSubject":{"id":"%s"}}}
        """.formatted(ISSUER, ISSUER, HOLDER));
    String vp = fixtureJwt("""
        {"iss":"%s","vp":{"type":["VerifiablePresentation"],"verifiableCredential":[%s]}}
        """.formatted(HOLDER, mapper.writeValueAsString(vc)));
    return new PresentationResponseMessage(List.of("https://w3id.org/dspace-dcp/v1.0/dcp.jsonld"),
        "PresentationResponseMessage", List.of(mapper.getNodeFactory().textNode(vp)), null);
  }

  // VC/VP signatures are checked at the explicitly mocked VerificationService boundary.
  private String fixtureJwt(String payload) {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    return encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
        + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".c2ln";
  }

  @Configuration @EnableWebMvc
  @Import({SecurityConfig.class, DcpMachineSecurityConfig.class, DcpOperationAuditConfig.class})
  static class Config {
    @Bean DcpProperties properties() { return new DcpProperties(); }
    @Bean CredentialServiceDiscovery discovery() { return mock(CredentialServiceDiscovery.class); }
    @Bean CredentialServiceClient client() { return mock(CredentialServiceClient.class); }
    @Bean DcpPresentationRequestService definitions() { return mock(DcpPresentationRequestService.class); }
    @Bean VerifierSiTokenFactory tokens() { return mock(VerifierSiTokenFactory.class); }
    @Bean VerificationService verification() { return mock(VerificationService.class); }
    @Bean JwtDecoder decoder() { return mock(JwtDecoder.class); }
    @Bean DcpReplayStore replay() { return mock(DcpReplayStore.class); }
    @Bean SelfIssuedIdTokenValidator validator(DidDocumentResolver resolver) {
      return new SelfIssuedIdTokenValidator(resolver, properties(), replay());
    }
    @Bean DcpVerifierService verifier(SelfIssuedIdTokenValidator validator) {
      return new DcpVerifierService(validator, discovery(), definitions(), tokens(), client());
    }
    @Bean DcpMachineAuthenticationService authentication(DcpVerifierService verifier) {
      return new DcpMachineAuthenticationService(verifier, verification(), properties());
    }
    @Bean ProbeController probe() { return new ProbeController(); }
  }

  @RestController static class ProbeController {
    @RequestMapping("/**") String probe(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) throws Exception {
      var authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication instanceof eu.xfsc.fc.core.security.DcpAuthenticationToken) {
        var identity = DcpParticipantAccess.requireIdentity(authentication);
        if (request.getRequestURI().equals("/participants/did:web:other.example")) {
          DcpParticipantAccess.checkAccess(authentication, "did:web:other.example");
        }
        response.setHeader("X-Participant", identity.participantDid());
      }
      return new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
