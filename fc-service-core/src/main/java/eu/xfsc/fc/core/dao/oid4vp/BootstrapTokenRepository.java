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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC repository for catalogue-local, short-lived OID4VP bootstrap tokens.
 *
 * <p>JDBC is used here to keep token persistence and atomic consume operations explicit. The
 * stored rows are indexed by JWT id and provide revocation and one-time-use state independent from
 * normal catalogue authentication tokens.
 */
@Repository
@RequiredArgsConstructor
public class BootstrapTokenRepository {

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Persists metadata for a newly issued bootstrap token.
   *
   * @param token token metadata and lifecycle timestamps to store
   */
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
        .addValue("createdAt", timestamp(token.createdAt()))
        .addValue("expiresAt", timestamp(token.expiresAt()))
        .addValue("redeemedAt", timestamp(token.redeemedAt()))
        .addValue("revokedAt", timestamp(token.revokedAt())));
  }

  /**
   * Finds a bootstrap token row by its JWT id.
   *
   * @param jti JWT id claim of the bootstrap token
   * @return token record if a row exists
   */
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

  /**
   * Atomically marks a token as redeemed only if it is still active.
   *
   * @param jti JWT id claim of the bootstrap token
   * @param redeemedAt redemption timestamp, also used for the expiry comparison
   * @return {@code true} if exactly one active row was updated
   */
  public boolean markRedeemedIfActive(String jti, Instant redeemedAt) {
    int updated = jdbc.update("""
        UPDATE oid4vp_bootstrap_token
           SET redeemed_at = :redeemedAt
         WHERE jti = :jti
           AND redeemed_at IS NULL
           AND revoked_at IS NULL
           AND expires_at > :redeemedAt
        """, Map.of("jti", jti, "redeemedAt", timestamp(redeemedAt)));
    return updated == 1;
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp timestamp = rs.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  /**
   * Maps one database row to a token record.
   *
   * @param rs current result set row
   * @param rowNum row number supplied by Spring JDBC
   * @return token record
   * @throws SQLException if a column cannot be read
   */
  private BootstrapTokenRecord mapToken(ResultSet rs, int rowNum) throws SQLException {
    return new BootstrapTokenRecord(
        rs.getString("jti"),
        rs.getString("holder_did"),
        rs.getString("connector_did"),
        rs.getString("membership_issuer"),
        rs.getString("presentation_id"),
        rs.getString("scope"),
        rs.getString("token_type"),
        instant(rs, "created_at"),
        instant(rs, "expires_at"),
        instant(rs, "redeemed_at"),
        instant(rs, "revoked_at"));
  }

  /**
   * Database projection for an OID4VP bootstrap token row.
   *
   * @param jti unique JWT id claim
   * @param holderDid participant DID proven during OID4VP
   * @param connectorDid connector DID for which the token is valid
   * @param membershipIssuer trusted issuer of the membership credential
   * @param presentationId identifier of the verified presentation
   * @param scope bootstrap token scope, currently {@code connector:bind}
   * @param tokenType JWT header type stored for auditability
   * @param createdAt token creation timestamp
   * @param expiresAt token expiry timestamp
   * @param redeemedAt redemption timestamp, or {@code null} if unused
   * @param revokedAt revocation timestamp, or {@code null} if not revoked
   */
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

