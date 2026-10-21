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
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.xfsc.fc.core.dao.ParticipantDao;
import eu.xfsc.fc.core.dao.validatorcache.ValidatorCacheDao;
import eu.xfsc.fc.core.pojo.AssetMetadata;
import eu.xfsc.fc.core.pojo.ContentAccessorDirect;
import eu.xfsc.fc.core.pojo.CredentialVerificationResult;
import eu.xfsc.fc.core.pojo.ParticipantMetaData;
import eu.xfsc.fc.core.security.DcpAuthenticationToken;
import eu.xfsc.fc.core.security.DcpIdentity;
import eu.xfsc.fc.core.service.assetstore.AssetStore;
import eu.xfsc.fc.core.service.verification.VerificationService;
import eu.xfsc.fc.server.generated.controller.ParticipantsApiController;
import eu.xfsc.fc.server.handler.RestExceptionHandler;
import eu.xfsc.fc.server.service.ParticipantsService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Real MVC, security and participant service; external persistence and verification are isolated. */
@SpringJUnitWebConfig(DcpOperationAuditTest.Config.class)
@TestPropertySource(properties = "keycloak.resource=federated-catalogue")
class DcpOperationAuditTest {
  private static final String DID = "did:web:participant.example";
  private static final String ACTOR = "did:web:connector.example";
  @Autowired WebApplicationContext context;
  @Autowired ParticipantDao participants;
  @Autowired AssetStore store;
  @Autowired VerificationService verification;
  private MockMvc mvc;
  private final Logger logger = (Logger) LoggerFactory.getLogger("eu.xfsc.fc.server.audit.DcpOperationAudit");
  private ListAppender<ILoggingEvent> events;
  private Level previousLevel;

  @BeforeEach void setup() {
    reset(participants, store, verification);
    previousLevel = logger.getLevel();
    logger.setLevel(Level.INFO);
    events = new ListAppender<>();
    events.start();
    logger.addAppender(events);
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @AfterEach void cleanup() {
    logger.detachAppender(events);
    logger.setLevel(previousLevel);
    events.stop();
    SecurityContextHolder.clearContext();
  }

  private DcpAuthenticationToken dcp(String actor) {
    return new DcpAuthenticationToken(new DcpIdentity(DID, actor));
  }

  private JsonNode event() throws Exception {
    assertEquals(1, events.list.size());
    String json = events.list.getFirst().getFormattedMessage();
    assertFalse(json.contains("secret"));
    assertFalse(json.contains("\n"));
    assertNull(events.list.getFirst().getThrowableProxy());
    return new ObjectMapper().readTree(json);
  }

  private void existingParticipant() {
    ParticipantMetaData participant = new ParticipantMetaData(DID, "name", null, "secret-credential");
    when(participants.select(DID)).thenReturn(Optional.of(participant));
    when(participants.selectForUpdate(DID)).thenReturn(Optional.of(participant));
    AssetMetadata asset = new AssetMetadata();
    asset.setContentAccessor(new ContentAccessorDirect("secret-credential"));
    when(store.getByHash(participant.getAssetHash())).thenReturn(asset);
    when(participants.delete(DID)).thenReturn(Optional.of(participant));
  }

  @Test void participantReadRecordsVerifiedIdentityOperationAndResourceWithoutPayloads() throws Exception {
    existingParticipant();
    mvc.perform(get("/participants/" + DID).queryParam("token", "secret-query")
        .header("Authorization", "Bearer secret-token").with(authentication(dcp(ACTOR))))
        .andExpect(status().isOk());
    JsonNode audit = event();
    assertEquals("SUCCESS", audit.get("outcome").asText());
    assertEquals(200, audit.get("httpStatus").asInt());
    assertEquals("GET /participants/{participantId}", audit.get("operation").asText());
    assertEquals("/participants/" + DID, audit.get("resource").asText());
    assertEquals(DID, audit.get("participantDid").asText());
    assertEquals(ACTOR, audit.get("actorDid").asText());
    assertEquals("DCP", audit.get("authenticationMethod").asText());
  }

  @Test void collectionReadDoesNotInventActor() throws Exception {
    when(participants.select(DID)).thenReturn(Optional.empty());
    mvc.perform(get("/participants").with(authentication(dcp(null)))).andExpect(status().isOk());
    JsonNode audit = event();
    assertEquals("/participants", audit.get("resource").asText());
    assertTrue(audit.get("actorDid").isNull());
  }

  @Test void participantCreationUsesCreatedResourceWithoutLoggingCredential() throws Exception {
    when(verification.verifyCredential(any())).thenReturn(new CredentialVerificationResult(
        Instant.now(), "ACTIVE", "did:web:issuer.example", Instant.now(), DID,
        List.of(), List.of(), null, null));
    when(participants.create(any())).thenAnswer(call -> call.getArgument(0));
    mvc.perform(post("/participants").with(csrf()).with(authentication(dcp(ACTOR)))
        .contentType("application/json").content("{\"credential\":\"secret-credential\"}"))
        .andExpect(status().isCreated());
    JsonNode audit = event();
    assertEquals("SUCCESS", audit.get("outcome").asText());
    assertEquals("POST /participants", audit.get("operation").asText());
    assertEquals("/participants/" + DID, audit.get("resource").asText());
    verify(participants).create(any());
  }

  @Test void participantDeletionHasOperationAuditWithoutDependingOnEnvers() throws Exception {
    existingParticipant();
    mvc.perform(delete("/participants/" + DID).with(csrf()).with(authentication(dcp(ACTOR))))
        .andExpect(status().isOk());
    assertEquals("DELETE /participants/{participantId}", event().get("operation").asText());
    verify(participants).delete(DID);
  }

  @Test void foreignParticipantDenialRetainsOnlyTheVerifiedCallerIdentity() throws Exception {
    mvc.perform(get("/participants/did:web:other.example").with(authentication(dcp(ACTOR))))
        .andExpect(status().isForbidden());
    JsonNode audit = event();
    assertEquals("DENIED", audit.get("outcome").asText());
    assertEquals(DID, audit.get("participantDid").asText());
    assertEquals("/participants/did:web:other.example", audit.get("resource").asText());
    verifyNoInteractions(participants, store);
  }

  @Test void keycloakClaimsAreNotAttributedAsVerifiedParticipant() throws Exception {
    mvc.perform(get("/participants/" + DID).with(jwt().jwt(jwt -> jwt.claim("participant_id", DID))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN_ALL"))))
        .andExpect(status().isForbidden());
    assertTrue(events.list.isEmpty(), "Rejected by the security chain before the MVC audit interceptor");
    verifyNoInteractions(participants, store);
  }

  @Test void identityWrappedInAnotherAuthenticationDoesNotEstablishTrust() throws Exception {
    var fake = UsernamePasswordAuthenticationToken.authenticated(new DcpIdentity(DID, ACTOR), "secret", List.of());
    mvc.perform(get("/participants/" + DID).with(authentication(fake))).andExpect(status().isForbidden());
    assertTrue(events.list.isEmpty());
  }

  @Test void unsuccessfulLookupIsNotReportedAsSuccess() throws Exception {
    when(participants.select(DID)).thenReturn(Optional.empty());
    mvc.perform(get("/participants/" + DID).with(authentication(dcp(ACTOR))))
        .andExpect(status().isNotFound());
    JsonNode audit = event();
    assertEquals("FAILED", audit.get("outcome").asText());
    assertEquals(404, audit.get("httpStatus").asInt());
  }

  @Test void securityRejectionBeforeMvcDoesNotProduceAuthenticatedAuditEvent() throws Exception {
    mvc.perform(get("/participants/" + DID)).andExpect(status().isUnauthorized());
    var unverified = dcp(ACTOR);
    unverified.setAuthenticated(false);
    mvc.perform(get("/participants/" + DID).with(authentication(unverified)))
        .andExpect(status().isForbidden());
    assertTrue(events.list.isEmpty());
    verifyNoInteractions(participants, store);
  }

  @Test void failingOperationDoesNotReportSuccessOrLogExceptionContents() throws Exception {
    when(participants.select(DID)).thenThrow(new IllegalStateException("secret-error-payload"));
    assertThrows(jakarta.servlet.ServletException.class,
        () -> mvc.perform(get("/participants").with(authentication(dcp(ACTOR)))));
    JsonNode audit = event();
    assertEquals("FAILED", audit.get("outcome").asText());
    assertTrue(audit.get("httpStatus").isNull());
  }

  @Test void identifiersAreJsonEscapedAndDoNotLeakIntoFollowingRequests() throws Exception {
    when(participants.select(DID)).thenReturn(Optional.empty());
    mvc.perform(get("/participants").with(authentication(dcp("did:web:actor\ninjected-line"))))
        .andExpect(status().isOk());
    assertEquals("did:web:actor\ninjected-line", event().get("actorDid").asText());
    events.list.clear();
    mvc.perform(get("/participants").with(jwt())).andExpect(status().isForbidden());
    assertTrue(events.list.isEmpty());

  }

  @Test void adminUserListingIsOutsideMachineAudit() throws Exception {
    mvc.perform(get("/participants/" + DID + "/users")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN_ALL"))))
        .andExpect(status().isGone());
    assertTrue(events.list.isEmpty());
  }

  @Configuration
  @EnableWebMvc
  @Import({SecurityConfig.class, DcpOperationAuditConfig.class, ParticipantsService.class, RestExceptionHandler.class})
  static class Config {
    @Bean ParticipantDao participants() { return mock(ParticipantDao.class); }
    @Bean AssetStore store() { return mock(AssetStore.class); }
    @Bean ValidatorCacheDao validatorCache() { return mock(ValidatorCacheDao.class); }
    @Bean VerificationService verification() { return mock(VerificationService.class); }
    @Bean JwtDecoder jwtDecoder() { return mock(JwtDecoder.class); }
    @Bean ParticipantsApiController controller(ParticipantsService service) { return new ParticipantsApiController(service); }
  }
}
