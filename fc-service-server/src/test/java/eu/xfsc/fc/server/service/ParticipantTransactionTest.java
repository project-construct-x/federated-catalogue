package eu.xfsc.fc.server.service;

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
import eu.xfsc.fc.core.config.DatabaseConfig;
import eu.xfsc.fc.core.dao.ParticipantDao;
import eu.xfsc.fc.core.dao.impl.ParticipantDaoImpl;
import eu.xfsc.fc.core.dao.validatorcache.ValidatorCacheDao;
import eu.xfsc.fc.core.exception.NotFoundException;
import eu.xfsc.fc.core.pojo.CredentialVerificationResult;
import eu.xfsc.fc.core.security.*;
import eu.xfsc.fc.core.service.assetstore.AssetStore;
import eu.xfsc.fc.core.service.verification.VerificationService;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.neo4j.Neo4jAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/** Real service transaction, PostgreSQL and Envers without a Keycloak or Neo4j bean. */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(classes = {ParticipantTransactionTest.Config.class, DatabaseConfig.class,
    ParticipantDaoImpl.class, SecurityAuditorAware.class, ParticipantsService.class})
@AutoConfigureEmbeddedDatabase(provider = DatabaseProvider.ZONKY)
class ParticipantTransactionTest {
  static final String DID = "did:web:company.example";
  @Autowired ParticipantsService service;
  @Autowired ParticipantDao dao;
  @Autowired AssetStore store;
  @Autowired VerificationService verification;
  @Autowired JdbcTemplate jdbc;
  @Configuration @EnableAutoConfiguration(exclude = Neo4jAutoConfiguration.class) static class Config {
    @Bean AssetStore store() { return mock(AssetStore.class); }
    @Bean VerificationService verification() { return mock(VerificationService.class); }
    @Bean ValidatorCacheDao validators() { return mock(ValidatorCacheDao.class); }
  }
  @BeforeEach void setup() {
    reset(store, verification);
    jdbc.execute("TRUNCATE catalogue_participants, catalogue_participants_aud");
    SecurityContextHolder.getContext().setAuthentication(new DcpAuthenticationToken(new DcpIdentity(DID, DID)));
    CredentialVerificationResult result = mock(CredentialVerificationResult.class);
    when(result.getId()).thenReturn(DID);
    when(verification.verifyCredential(any())).thenReturn(result);
    when(store.getByHash(anyString())).thenThrow(new NotFoundException("missing"));
  }
  @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
  @Test void downstreamFailureRollsBackReservedParticipantAndAudit() {
    doAnswer(call -> {
      assertTrue(dao.select(DID).isPresent(), "DID must be reserved before publishing");
      throw new IllegalStateException("asset persistence failed");
    }).when(store).storeCredential(any(), any());
    assertThrows(IllegalStateException.class, () -> service.addParticipant("credential"));
    assertTrue(dao.select(DID).isEmpty());
    assertEquals(0, jdbc.queryForObject("select count(*) from catalogue_participants_aud", Integer.class));
  }
  @Test void successfulRegistrationCommitsParticipantWithDcpAudit() {
    assertEquals(201, service.addParticipant("credential").getStatusCode().value());
    assertTrue(dao.select(DID).isPresent());
    assertEquals(DID, jdbc.queryForObject("select r.participant_did from revinfo r join "
        + "catalogue_participants_aud p on p.rev=r.rev", String.class));
    assertEquals("DCP", jdbc.queryForObject("select r.authentication_method from revinfo r join "
        + "catalogue_participants_aud p on p.rev=r.rev", String.class));
  }
}
