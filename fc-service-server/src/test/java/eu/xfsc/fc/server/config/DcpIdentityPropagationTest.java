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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.xfsc.fc.core.dao.ParticipantDao;
import eu.xfsc.fc.core.dao.audit.AuthenticationRevision;
import eu.xfsc.fc.core.dao.audit.DcpRevisionListener;
import eu.xfsc.fc.core.dao.validatorcache.ValidatorCacheDao;
import eu.xfsc.fc.core.pojo.CredentialVerificationResult;
import eu.xfsc.fc.core.pojo.ParticipantMetaData;
import eu.xfsc.fc.core.security.DcpIdentity;
import eu.xfsc.fc.core.security.SecurityAuditorAware;
import eu.xfsc.fc.core.service.assetstore.AssetStore;
import eu.xfsc.fc.core.service.dcp.DcpMachineAuthenticationService;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Real incoming filter, participant controller/service and audit consumers; verification/storage isolated. */
@SpringJUnitWebConfig(DcpIdentityPropagationTest.Config.class)
@TestPropertySource(properties = "keycloak.resource=federated-catalogue")
class DcpIdentityPropagationTest {
  private static final String DID = "did:web:participant.example";
  private static final String ACTOR = "did:web:connector.example";
  private static final String OTHER = "did:web:other.example";
  @Autowired WebApplicationContext context;
  @Autowired DcpMachineAuthenticationService authentication;
  @Autowired ParticipantDao participants;
  @Autowired AssetStore store;
  @Autowired VerificationService verification;
  private MockMvc mvc;
  private final Logger logger = (Logger) LoggerFactory.getLogger("eu.xfsc.fc.server.audit.DcpOperationAudit");
  private ListAppender<ILoggingEvent> events;
  private Level previousLevel;

  @BeforeEach void setup() {
    reset(authentication, participants, store, verification);
    when(authentication.authenticateMachineAccess(any())).thenThrow(new BadCredentialsException("Invalid DCP"));
    doReturn(new DcpIdentity(DID, ACTOR)).when(authentication).authenticateMachineAccess("Bearer verified-dcp");
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

  private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
    return request.header("Authorization", "Bearer verified-dcp")
        .header("X-Participant-Id", OTHER).header("X-Actor-Did", OTHER)
        .queryParam("participant_id", OTHER).queryParam("participantDid", OTHER)
        .queryParam("actorDid", OTHER);
  }

  private void credentialSubject(String subject) {
    when(verification.verifyCredential(any())).thenReturn(new CredentialVerificationResult(
        Instant.now(), "ACTIVE", "did:web:issuer.example", Instant.now(), subject,
        List.of(), List.of(), null, null));
  }

  private void assertAudit(String outcome) throws Exception {
    assertEquals(1, events.list.size());
    var audit = new ObjectMapper().readTree(events.list.getFirst().getFormattedMessage());
    assertEquals(DID, audit.get("participantDid").asText());
    assertEquals(ACTOR, audit.get("actorDid").asText());
    assertEquals("DCP", audit.get("authenticationMethod").asText());
    assertEquals(outcome, audit.get("outcome").asText());
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test void collectionScopeIgnoresParticipantHeadersAndQueryParameters() throws Exception {
    mvc.perform(authenticated(get("/participants"))).andExpect(status().isOk());
    verify(participants).select(DID);
    verifyNoMoreInteractions(participants);
    assertAudit("SUCCESS");
  }

  @Test void creationPropagatesSameIdentityToOwnershipAndBothPersistenceAuditConsumers() throws Exception {
    credentialSubject(DID);
    when(participants.create(any())).thenAnswer(call -> {
      ParticipantMetaData participant = call.getArgument(0);
      assertEquals(DID, participant.getId());
      // Exercise the actual persistence audit callbacks while the HTTP filter's context is active.
      // Database persistence/Envers wiring is covered separately by ParticipantDaoTest.
      assertEquals(Optional.of(DID), new SecurityAuditorAware().getCurrentAuditor());
      var revision = new AuthenticationRevision();
      new DcpRevisionListener().newRevision(revision);
      assertEquals(DID, revision.getParticipantDid());
      assertEquals(ACTOR, revision.getActorDid());
      assertEquals("DCP", revision.getAuthenticationMethod());
      return participant;
    });
    mvc.perform(authenticated(post("/participants")).contentType("application/json")
        .content("{\"participant_id\":\"" + OTHER + "\",\"actorDid\":\"" + OTHER + "\"}"))
        .andExpect(status().isCreated());
    verify(participants).create(any());
    verify(store).storeCredential(any(), any());
    assertAudit("SUCCESS");
  }

  @ParameterizedTest
  @ValueSource(strings = {"GET", "PUT", "DELETE"})
  void foreignPathCannotReplaceVerifiedCaller(String method) throws Exception {
    mvc.perform(authenticated(request(HttpMethod.valueOf(method), "/participants/" + OTHER))
        .contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(participants, store, verification);
    assertAudit("DENIED");
  }

  @Test void registrationOfForeignCredentialSubjectCannotReplaceVerifiedCaller() throws Exception {
    credentialSubject(OTHER);
    mvc.perform(authenticated(post("/participants")).contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(participants, store);
    assertAudit("DENIED");
  }

  @Test void updateCannotChangeParticipantThroughCredentialBody() throws Exception {
    credentialSubject(OTHER);
    when(participants.selectForUpdate(DID)).thenReturn(Optional.of(new ParticipantMetaData(DID, "name", null, "{}")));
    mvc.perform(authenticated(put("/participants/" + DID)).contentType("application/json").content("{}"))
        .andExpect(status().isBadRequest());
    verify(participants, never()).update(anyString(), any());
    verifyNoInteractions(store);
    assertAudit("FAILED");
  }

  @Test void followingUnauthenticatedRequestCannotReuseCallerOrAuditAttribution() throws Exception {
    mvc.perform(authenticated(get("/participants"))).andExpect(status().isOk());
    assertAudit("SUCCESS");
    clearInvocations(participants);
    events.list.clear();
    mvc.perform(get("/participants").queryParam("participant_id", DID)).andExpect(status().isUnauthorized());
    verifyNoInteractions(participants, store);
    assertTrue(events.list.isEmpty());
    assertNull(SecurityContextHolder.getContext().getAuthentication());
    assertTrue(new SecurityAuditorAware().getCurrentAuditor().isEmpty());
  }

  @Configuration
  @EnableWebMvc
  @Import({SecurityConfig.class, DcpMachineSecurityConfig.class, DcpOperationAuditConfig.class,
      ParticipantsService.class, RestExceptionHandler.class})
  static class Config {
    @Bean DcpMachineAuthenticationService authentication() { return mock(DcpMachineAuthenticationService.class); }
    @Bean ParticipantDao participantDao() { return mock(ParticipantDao.class); }
    @Bean AssetStore store() { return mock(AssetStore.class); }
    @Bean ValidatorCacheDao validatorCache() { return mock(ValidatorCacheDao.class); }
    @Bean VerificationService verification() { return mock(VerificationService.class); }
    @Bean JwtDecoder jwtDecoder() { return mock(JwtDecoder.class); }
    @Bean ParticipantsApiController controller(ParticipantsService service) { return new ParticipantsApiController(service); }
  }
}
