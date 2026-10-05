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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import eu.xfsc.fc.core.dao.ParticipantDao;
import eu.xfsc.fc.core.dao.validatorcache.ValidatorCacheDao;
import eu.xfsc.fc.core.pojo.*;
import eu.xfsc.fc.core.security.*;
import eu.xfsc.fc.core.service.assetstore.AssetStore;
import eu.xfsc.fc.core.service.verification.VerificationService;
import eu.xfsc.fc.server.config.SecurityConfig;
import eu.xfsc.fc.server.generated.controller.ParticipantsApiController;
import eu.xfsc.fc.server.handler.RestExceptionHandler;
import eu.xfsc.fc.server.service.ParticipantsService;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(ParticipantsControllerTest.Config.class)
@TestPropertySource(properties = "keycloak.resource=federated-catalogue")
class ParticipantsControllerTest {
  static final String DID = "did:web:company.example";
  static final String HASH = "a".repeat(64);
  // JSON fixtures only; credential verification is mocked in these ownership tests.
  static final String CREDENTIAL = "{\"type\":\"VerifiableCredential\"}";
  static final String REPLACEMENT_CREDENTIAL = "{\"type\":\"VerifiableCredential\",\"name\":\"replacement\"}";
  @Autowired WebApplicationContext context;
  @Autowired ParticipantDao dao;
  @Autowired AssetStore store;
  @Autowired VerificationService verification;
  MockMvc mvc;
  DcpAuthenticationToken dcp() { return new DcpAuthenticationToken(new DcpIdentity(DID, DID)); }
  ParticipantMetaData participant() { return new ParticipantMetaData(DID, "Company", null, null, HASH); }
  void existing() {
    when(dao.select(DID)).thenReturn(Optional.of(participant()));
    when(dao.selectForUpdate(DID)).thenReturn(Optional.of(participant()));
    AssetMetadata asset = new AssetMetadata();
    asset.setContentAccessor(new ContentAccessorDirect(CREDENTIAL));
    when(store.getByHash(HASH)).thenReturn(asset);
  }
  void credential(String subject) {
    CredentialVerificationResult result = mock(CredentialVerificationResult.class);
    when(result.getId()).thenReturn(subject);
    when(result.getName()).thenReturn("Company");
    when(verification.verifyCredential(any())).thenReturn(result);
  }
  @BeforeEach void setup() {
    reset(dao, store, verification);
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }
  @Test void registersOwnDidWithoutKeycloakAndReturnsCredential() throws Exception {
    credential(DID);
    when(dao.create(any())).thenAnswer(call -> call.getArgument(0));
    mvc.perform(post("/participants").with(authentication(dcp())).with(csrf())
        .contentType("application/json").content(CREDENTIAL))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(DID))
        .andExpect(jsonPath("$.asset").value(CREDENTIAL));
    verify(store).storeCredential(any(), any());
    verify(dao).create(any());
  }
  @Test void registrationRejectsOtherSubjectBeforeWriting() throws Exception {
    credential("did:web:other");
    mvc.perform(post("/participants").with(authentication(dcp())).with(csrf())
        .contentType("application/json").content(CREDENTIAL)).andExpect(status().isForbidden());
    verifyNoInteractions(dao, store);
  }
  @Test void duplicateRegistrationDoesNotOverwriteCredential() throws Exception {
    credential(DID);
    when(dao.select(DID)).thenReturn(Optional.of(participant()));
    mvc.perform(post("/participants").with(authentication(dcp())).with(csrf())
        .contentType("application/json").content(REPLACEMENT_CREDENTIAL)).andExpect(status().isConflict());
    verifyNoInteractions(store);
    verify(dao, never()).create(any());
  }
  @Test void readsOnlyOwnParticipantAndScopesCollectionIncludingEmptyPage() throws Exception {
    existing();
    mvc.perform(get("/participants/" + DID).with(authentication(dcp())))
        .andExpect(status().isOk()).andExpect(jsonPath("$.asset").value(CREDENTIAL));
    mvc.perform(get("/participants/did:web:other").with(authentication(dcp())))
        .andExpect(status().isForbidden());
    mvc.perform(get("/participants").with(authentication(dcp())))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(1));
    mvc.perform(get("/participants?offset=1").with(authentication(dcp())))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(1))
        .andExpect(jsonPath("$.items").isEmpty());
    verify(dao, never()).search(any(), any());
    verify(dao, never()).select("did:web:other");
  }
  @Test void deletesOnlyOwnParticipantWithoutUserSideEffects() throws Exception {
    existing();
    when(dao.delete(DID)).thenReturn(Optional.of(participant()));
    mvc.perform(delete("/participants/" + DID).with(authentication(dcp())).with(csrf()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.asset").value(CREDENTIAL));
    verify(store).deleteAsset(HASH);
    verify(dao).delete(DID);
    mvc.perform(delete("/participants/did:web:other").with(authentication(dcp())).with(csrf()))
        .andExpect(status().isForbidden());
    verify(dao, never()).delete("did:web:other");
  }
  @Test void updateCannotChangeDidOrWriteForAnotherParticipant() throws Exception {
    existing();
    credential("did:web:other");
    mvc.perform(put("/participants/" + DID).with(authentication(dcp())).with(csrf())
        .contentType("application/json").content(CREDENTIAL)).andExpect(status().isBadRequest());
    mvc.perform(put("/participants/did:web:other").with(authentication(dcp())).with(csrf())
        .contentType("application/json").content(CREDENTIAL)).andExpect(status().isForbidden());
    verify(store, never()).storeCredential(any(), any());
    verify(dao, never()).update(any(), any());
  }
  @Test void updatesOwnParticipantAndReturnsNewCredential() throws Exception {
    existing();
    credential(DID);
    when(dao.update(eq(DID), any())).thenReturn(Optional.of(participant()));
    mvc.perform(put("/participants/" + DID).with(authentication(dcp())).with(csrf())
        .contentType("application/json").content(CREDENTIAL))
        .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(DID))
        .andExpect(jsonPath("$.asset").value(CREDENTIAL));
    var order = inOrder(dao, store);
    order.verify(dao).selectForUpdate(DID);
    order.verify(store).storeCredential(any(), any());
    order.verify(dao).update(eq(DID), any());
  }
  @Test void reRegistrationReusesIdenticalActiveCredentialAfterFreshVerification() throws Exception {
    credential(DID);
    AssetMetadata previous = new AssetMetadata();
    previous.setId(DID);
    previous.setStatus(eu.xfsc.fc.api.generated.model.AssetStatus.ACTIVE);
    previous.setContentAccessor(new ContentAccessorDirect(CREDENTIAL));
    when(store.getByHash(anyString())).thenReturn(previous);
    when(dao.create(any())).thenAnswer(call -> call.getArgument(0));
    mvc.perform(post("/participants").with(authentication(dcp())).with(csrf())
        .contentType("application/json").content(CREDENTIAL)).andExpect(status().isCreated());
    verify(verification).verifyCredential(any());
    verify(store, never()).storeCredential(any(), any());
    verify(dao).create(any());
  }
  @Test void reRegistrationCannotAdoptRevokedCredential() throws Exception {
    credential(DID);
    AssetMetadata previous = new AssetMetadata();
    previous.setId(DID);
    previous.setStatus(eu.xfsc.fc.api.generated.model.AssetStatus.REVOKED);
    previous.setContentAccessor(new ContentAccessorDirect(CREDENTIAL));
    when(store.getByHash(anyString())).thenReturn(previous);
    mvc.perform(post("/participants").with(authentication(dcp())).with(csrf())
        .contentType("application/json").content(CREDENTIAL)).andExpect(status().isConflict());
    verify(dao, never()).create(any());
    verify(store, never()).storeCredential(any(), any());
  }
  @Test void missingParticipantReturns404AndEmptyCollection() throws Exception {
    mvc.perform(get("/participants/" + DID).with(authentication(dcp()))).andExpect(status().isNotFound());
    mvc.perform(get("/participants").with(authentication(dcp())))
        .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(0))
        .andExpect(jsonPath("$.items").isEmpty());
  }
  @Test void jwtClaimsAndAdminRoleCannotReplaceDcp() throws Exception {
    mvc.perform(get("/participants/" + DID).with(jwt().jwt(j -> j.claim("participant_id", DID))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN_ALL")))).andExpect(status().isForbidden());
    verifyNoInteractions(dao, store, verification);
  }
  @Test void retiredUserAssociationReturnsGoneForAdmin() throws Exception {
    mvc.perform(get("/participants/" + DID + "/users")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN_ALL"))))
        .andExpect(status().isGone());
    verifyNoInteractions(dao, store);
  }
  @Configuration @EnableWebMvc
  @Import({SecurityConfig.class, ParticipantsService.class, RestExceptionHandler.class})
  static class Config {
    @Bean ParticipantDao participants() { return mock(ParticipantDao.class); }
    @Bean AssetStore assets() { return mock(AssetStore.class); }
    @Bean VerificationService verification() { return mock(VerificationService.class); }
    @Bean ValidatorCacheDao validators() { return mock(ValidatorCacheDao.class); }
    @Bean JwtDecoder jwtDecoder() { return mock(JwtDecoder.class); }
    @Bean ParticipantsApiController controller(ParticipantsService service) { return new ParticipantsApiController(service); }
  }
}
