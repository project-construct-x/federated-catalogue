package eu.xfsc.fc.core.dao.oid4vp;

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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC repository for catalogue-local, short-lived OID4VP bootstrap tokens. */
@Repository
@RequiredArgsConstructor
public class BootstrapTokenRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public void save(BootstrapTokenRecord token) {
    jdbc.update("""
        INSERT INTO oid4vp_bootstrap_token
          (id, jti, connector_did, holder_did, membership_issuer, presentation_id,
           scope, token_type, created_at, expires_at, redeemed_at, revoked_at)
        VALUES
          (:id, :jti, :connectorDid, :holderDid, :membershipIssuer, :presentationId,
           :scope, :tokenType, :createdAt, :expiresAt, :redeemedAt, :revokedAt)
        """, new MapSqlParameterSource()
        .addValue("id", UUID.randomUUID())
        .addValue("jti", token.jti())
        .addValue("connectorDid", token.connectorDid())
        .addValue("holderDid", token.holderDid())
        .addValue("membershipIssuer", token.membershipIssuer())
        .addValue("presentationId", token.presentationId())
        .addValue("scope", token.scope())
        .addValue("tokenType", token.tokenType())
        .addValue("createdAt", token.createdAt())
        .addValue("expiresAt", token.expiresAt())
        .addValue("redeemedAt", token.redeemedAt())
        .addValue("revokedAt", token.revokedAt()));
  }

  public Optional<BootstrapTokenRecord> findByJti(String jti) {
    try {
      return Optional.ofNullable(jdbc.queryForObject("""
          SELECT jti, connector_did, holder_did, membership_issuer, presentation_id,
                 scope, token_type, created_at, expires_at, redeemed_at, revoked_at
            FROM oid4vp_bootstrap_token
           WHERE jti = :jti
          """, Map.of("jti", jti), this::mapToken));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  public boolean markRedeemedIfActive(String jti, Instant redeemedAt) {
    int updated = jdbc.update("""
        UPDATE oid4vp_bootstrap_token
           SET redeemed_at = :redeemedAt
         WHERE jti = :jti
           AND redeemed_at IS NULL
           AND revoked_at IS NULL
           AND expires_at > :redeemedAt
        """, Map.of("jti", jti, "redeemedAt", redeemedAt));
    return updated == 1;
  }

  private BootstrapTokenRecord mapToken(ResultSet rs, int rowNum) throws SQLException {
    return new BootstrapTokenRecord(
        rs.getString("jti"),
        rs.getString("holder_did"),
        rs.getString("connector_did"),
        rs.getString("membership_issuer"),
        rs.getString("presentation_id"),
        rs.getString("scope"),
        rs.getString("token_type"),
        rs.getObject("created_at", Instant.class),
        rs.getObject("expires_at", Instant.class),
        rs.getObject("redeemed_at", Instant.class),
        rs.getObject("revoked_at", Instant.class));
  }

  public record BootstrapTokenRecord(
      String jti,
      String holderDid,
      String connectorDid,
      String membershipIssuer,
      String presentationId,
      String scope,
      String tokenType,
      Instant createdAt,
      Instant expiresAt,
      Instant redeemedAt,
      Instant revokedAt
  ) {
  }
}

