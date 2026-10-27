package eu.xfsc.fc.core.dao;

/*-
 * ---license-start
 * fc-service-core
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
import eu.xfsc.fc.core.config.DatabaseConfig;
import eu.xfsc.fc.core.dao.impl.ParticipantDaoImpl;
import eu.xfsc.fc.core.exception.ClientException;
import eu.xfsc.fc.core.exception.ConflictException;
import eu.xfsc.fc.core.pojo.ParticipantMetaData;
import eu.xfsc.fc.core.security.*;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL, Liquibase and Envers; deliberately no Keycloak or graph server. */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(classes = {ParticipantDaoTest.Config.class, DatabaseConfig.class,
    ParticipantDaoImpl.class, SecurityAuditorAware.class})
@AutoConfigureEmbeddedDatabase(provider = DatabaseProvider.ZONKY)
class ParticipantDaoTest {
  @Configuration @EnableAutoConfiguration static class Config { }
  static final String DID = "did:web:company.example";
  @Autowired ParticipantDao dao;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;

  @BeforeEach void setup() {
    SecurityContextHolder.clearContext();
    jdbc.execute("TRUNCATE catalogue_participants, catalogue_participants_aud");
    authenticate(DID, "did:web:holder.example");
  }
  @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
  void authenticate(String did, String actor) {
    SecurityContextHolder.getContext().setAuthentication(new DcpAuthenticationToken(new DcpIdentity(did, actor)));
  }
  ParticipantMetaData participant(String did, String name) {
    return new ParticipantMetaData(did, name, null, null, "a".repeat(64));
  }

  @Test void persistsAcrossTransactionsWithoutKeycloakAndUsesExactDid() {
    dao.create(participant(DID, "Company"));
    var loaded = dao.select(DID).orElseThrow();
    assertEquals("Company", loaded.getName());
    assertNull(loaded.getPublicKey());
    assertEquals("a".repeat(64), loaded.getAssetHash());
    assertTrue(dao.select("did:web:company").isEmpty());
    assertEquals(DID, jdbc.queryForObject("select created_by from catalogue_participants", String.class));
  }

  @Test void duplicateDoesNotOverwriteAndRenameIsRejected() {
    dao.create(participant(DID, "Original"));
    assertThrows(ConflictException.class, () -> dao.create(participant(DID, "Overwrite")));
    assertThrows(ClientException.class, () -> dao.update(DID, participant("did:web:other", "Rename")));
    assertEquals("Original", dao.select(DID).orElseThrow().getName());
    assertEquals(1, jdbc.queryForObject("select count(*) from catalogue_participants_aud", Integer.class));
  }

  @Test void mutationsRequireVerifiedDcpOwnerIncludingAbsentRows() {
    dao.create(participant(DID, "Original"));
    authenticate("did:web:other", null);
    assertThrows(AccessDeniedException.class, () -> dao.update(DID, participant(DID, "Other")));
    assertThrows(AccessDeniedException.class, () -> dao.delete(DID));
    assertThrows(AccessDeniedException.class, () -> dao.delete("did:web:missing"));
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
        new DcpIdentity(DID, null), "", List.of()));
    assertThrows(AccessDeniedException.class, () -> dao.create(participant(DID, "Fake")));
    assertEquals("Original", dao.select(DID).orElseThrow().getName());
  }

  @Test void createUpdateDeleteRevisionsRecordVerifiedCallerIncludingDeletion() {
    dao.create(participant(DID, "First"));
    authenticate(DID, "did:web:second-holder");
    dao.update(DID, participant(DID, "Second"));
    authenticate(DID, "did:web:deleting-holder");
    assertEquals("Second", dao.delete(DID).orElseThrow().getName());
    assertTrue(dao.select(DID).isEmpty());
    var rows = jdbc.queryForList("select a.revtype, a.name, r.participant_did, r.actor_did, "
        + "r.authentication_method from catalogue_participants_aud a join revinfo r on a.rev=r.rev order by r.rev");
    assertEquals(3, rows.size());
    for (int i = 0; i < 3; i++) {
      assertEquals(i, ((Number) rows.get(i).get("revtype")).intValue());
      assertEquals(DID, rows.get(i).get("participant_did"));
      assertEquals("DCP", rows.get(i).get("authentication_method"));
    }
    assertEquals("did:web:deleting-holder", rows.get(2).get("actor_did"));
    assertEquals("Second", rows.get(2).get("name"));
  }

  @Test void enclosingRollbackRemovesParticipantAndRevision() {
    tx.executeWithoutResult(status -> {
      dao.create(participant(DID, "Rolled back"));
      status.setRollbackOnly();
    });
    assertTrue(dao.select(DID).isEmpty());
    assertEquals(0, jdbc.queryForObject("select count(*) from catalogue_participants_aud", Integer.class));
  }

  @Test void rollbackOfUpdateAndDeletePreservesMetadataAndAuditHistory() {
    dao.create(participant(DID, "Original"));
    tx.executeWithoutResult(status -> {
      dao.update(DID, participant(DID, "Temporary"));
      dao.delete(DID);
      status.setRollbackOnly();
    });
    assertEquals("Original", dao.select(DID).orElseThrow().getName());
    assertEquals(1, jdbc.queryForObject("select count(*) from catalogue_participants_aud", Integer.class));
  }

  @Test void simultaneousRegistrationCannotOverwriteOrCreateTwoRevisions() throws Exception {
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var ready = new java.util.concurrent.CountDownLatch(2);
      var start = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<Boolean> register = () -> {
        authenticate(DID, "did:web:holder.example");
        ready.countDown();
        try {
          assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
          dao.create(participant(DID, "Concurrent"));
          return true;
        } catch (ConflictException expected) {
          return false;
        } finally {
          SecurityContextHolder.clearContext();
        }
      };
      var first = executor.submit(register);
      var second = executor.submit(register);
      assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
      start.countDown();
      assertNotEquals(first.get(20, java.util.concurrent.TimeUnit.SECONDS),
          second.get(20, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(1, jdbc.queryForObject("select count(*) from catalogue_participants", Integer.class));
      assertEquals(1, jdbc.queryForObject("select count(*) from catalogue_participants_aud", Integer.class));
    }
  }

  @Test void deterministicPaginationRetainsTotalBeyondLastPage() {
    dao.create(participant(DID, "Company"));
    authenticate(DID + ":branch", null);
    dao.create(participant(DID + ":branch", "Branch"));
    assertEquals(DID, dao.search(0, 1).getResults().getFirst().getId());
    assertEquals(DID + ":branch", dao.search(1, 1).getResults().getFirst().getId());
    assertEquals(2, dao.search(0, 0).getTotalCount());
    assertTrue(dao.search(0, 0).getResults().isEmpty());
    assertEquals(2, dao.search(2, 1).getTotalCount());
    assertTrue(dao.search(2, 1).getResults().isEmpty());
    assertThrows(ClientException.class, () -> dao.search(-1, 10));
  }
}
