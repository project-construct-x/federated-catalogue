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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Shared, durable single-use SI-token registry; no bearer tokens or credentials are stored. */
@Repository
public class DcpReplayStore {
  private final JdbcTemplate jdbc;

  public DcpReplayStore(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  /** Commit consumption even if subsequent membership verification or the operation fails. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean consume(String issuer, String jti, Instant expiresAt) {
    jdbc.update("DELETE FROM dcp_used_si_tokens WHERE expires_at <= CURRENT_TIMESTAMP");
    return jdbc.update("""
        INSERT INTO dcp_used_si_tokens (issuer_hash, jti_hash, expires_at)
        SELECT ?, ?, ? WHERE ? > CURRENT_TIMESTAMP
        ON CONFLICT (issuer_hash, jti_hash) DO NOTHING
        """, hash(issuer), hash(jti), Timestamp.from(expiresAt), Timestamp.from(expiresAt)) == 1;
  }

  private static String hash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}