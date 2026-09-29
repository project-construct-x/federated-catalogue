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

import eu.xfsc.fc.core.service.oid4vp.Challenge;
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

/** JDBC repository for catalogue-local OID4VP connector binding challenges. */
@Repository
@RequiredArgsConstructor
public class ConnectorBindingChallengeRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public void save(Challenge challenge) {
    jdbc.update("""
        INSERT INTO oid4vp_binding_challenge
          (id, connector_did, challenge_id, nonce, state, holder_did, presentation_id,
           created_at, expires_at, consumed_at, revoked_at)
        VALUES
          (:id, :connectorDid, :challengeId, :nonce, :state, :holderDid, :presentationId,
           :createdAt, :expiresAt, :consumedAt, :revokedAt)
        """, new MapSqlParameterSource()
        .addValue("id", UUID.randomUUID())
        .addValue("connectorDid", challenge.connectorDid())
        .addValue("challengeId", challenge.id())
        .addValue("nonce", challenge.nonce())
        .addValue("state", challenge.state())
        .addValue("holderDid", null)
        .addValue("presentationId", null)
        .addValue("createdAt", challenge.createdAt())
        .addValue("expiresAt", challenge.expiresAt())
        .addValue("consumedAt", null)
        .addValue("revokedAt", null));
  }

  public Optional<Challenge> findByState(String state) {
    try {
      return Optional.ofNullable(jdbc.queryForObject("""
          SELECT challenge_id, connector_did, nonce, state, created_at, expires_at
            FROM oid4vp_binding_challenge
           WHERE state = :state
          """, Map.of("state", state), this::mapChallenge));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  public Optional<Challenge> findActiveByState(String state, Instant now) {
    try {
      return Optional.ofNullable(jdbc.queryForObject("""
          SELECT challenge_id, connector_did, nonce, state, created_at, expires_at
            FROM oid4vp_binding_challenge
           WHERE state = :state
             AND consumed_at IS NULL
             AND revoked_at IS NULL
             AND expires_at > :now
          """, Map.of("state", state, "now", now), this::mapChallenge));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  public Optional<Challenge> findActiveByChallengeIdAndConnectorDid(
      String challengeId, String connectorDid, Instant now) {
    try {
      return Optional.ofNullable(jdbc.queryForObject("""
          SELECT challenge_id, connector_did, nonce, state, created_at, expires_at
            FROM oid4vp_binding_challenge
           WHERE challenge_id = :challengeId
             AND connector_did = :connectorDid
             AND consumed_at IS NULL
             AND revoked_at IS NULL
             AND expires_at > :now
          """, Map.of(
          "challengeId", challengeId,
          "connectorDid", connectorDid,
          "now", now), this::mapChallenge));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  public boolean consumeIfActive(String challengeId, String connectorDid, Instant consumedAt) {
    int updated = jdbc.update("""
        UPDATE oid4vp_binding_challenge
           SET consumed_at = :consumedAt
         WHERE challenge_id = :challengeId
           AND connector_did = :connectorDid
           AND consumed_at IS NULL
           AND revoked_at IS NULL
           AND expires_at > :consumedAt
        """, Map.of(
        "challengeId", challengeId,
        "connectorDid", connectorDid,
        "consumedAt", consumedAt));
    return updated == 1;
  }

  private Challenge mapChallenge(ResultSet rs, int rowNum) throws SQLException {
    return new Challenge(
        rs.getString("challenge_id"),
        rs.getString("connector_did"),
        rs.getString("nonce"),
        rs.getString("state"),
        rs.getObject("created_at", Instant.class),
        rs.getObject("expires_at", Instant.class));
  }
}
