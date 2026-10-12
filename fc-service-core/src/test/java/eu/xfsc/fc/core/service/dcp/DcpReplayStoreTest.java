package eu.xfsc.fc.core.service.dcp;

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
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL and the actual Liquibase migration; no application/graph services. */
@SpringBootTest(classes = DcpReplayStoreTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.liquibase.change-log=classpath:/liquibase/master-changelog.xml")
@AutoConfigureEmbeddedDatabase(provider = DatabaseProvider.ZONKY)
class DcpReplayStoreTest {
  @Configuration @EnableTransactionManagement @Import(DcpReplayStore.class)
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class, LiquibaseAutoConfiguration.class})
  static class Config { }
  @Autowired DcpReplayStore store;
  @Autowired DataSource dataSource;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;

  @BeforeEach void clear() { jdbc.update("DELETE FROM dcp_used_si_tokens"); }

  @Test void survivesNewStoreInstancesAndSeparatesIssuers() {
    Instant expiry = Instant.now().plusSeconds(7200);
    assertTrue(store.consume("did:web:first", "same-jti", expiry));
    assertFalse(new DcpReplayStore(dataSource).consume("did:web:first", "same-jti", expiry));
    assertTrue(store.consume("did:web:second", "same-jti", expiry));
    var hashes = jdbc.queryForList("SELECT issuer_hash, jti_hash FROM dcp_used_si_tokens");
    assertEquals(2, hashes.size());
    assertTrue(hashes.stream().flatMap(row -> row.values().stream())
        .allMatch(value -> value.toString().matches("[0-9a-f]{64}")));
  }

  @Test void onlyOneConcurrentRequestConsumesAToken() throws Exception {
    try (var pool = Executors.newFixedThreadPool(8)) {
      var calls = new ArrayList<Callable<Boolean>>();
      for (int i = 0; i < 8; i++) {
        calls.add(() -> store.consume("did:web:holder", "concurrent", Instant.now().plusSeconds(300)));
      }
      int accepted = 0;
      for (var result : pool.invokeAll(calls)) {
        if (result.get()) { accepted++; }
      }
      assertEquals(1, accepted);
    }
  }

  @Test void consumptionSurvivesBusinessTransactionRollback() {
    Instant expiry = Instant.now().plusSeconds(300);
    assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).execute(status -> {
      assertTrue(store.consume("did:web:holder", "rollback", expiry));
      throw new IllegalStateException("operation failed");
    }));
    assertFalse(store.consume("did:web:holder", "rollback", expiry));
  }

  @Test void rejectsExpiredTokensAndCleansOnlyExpiredRecords() {
    assertFalse(store.consume("did:web:holder", "expired", Instant.now().minusSeconds(1)));
    assertTrue(store.consume("did:web:holder", "old", Instant.now().plusSeconds(300)));
    jdbc.update("UPDATE dcp_used_si_tokens SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");
    assertTrue(store.consume("did:web:holder", "new", Instant.now().plusSeconds(300)));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM dcp_used_si_tokens", Integer.class));
    assertFalse(store.consume("did:web:holder", "new", Instant.now().plusSeconds(300)));
  }
}