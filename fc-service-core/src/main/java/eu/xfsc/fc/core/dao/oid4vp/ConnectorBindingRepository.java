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

import eu.xfsc.fc.core.service.oid4vp.ConnectorBinding;
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
 * JDBC repository for connector-to-participant bindings created after OID4VP bootstrap.
 *
 * <p>The repository writes the Liquibase-defined {@code oid4vp_connector_binding} table and keeps
 * idempotent insert semantics for repeated binding calls of the same connector DID.
 */
@Repository
@RequiredArgsConstructor
public class ConnectorBindingRepository {

  private static final String STATUS_ACTIVE = "ACTIVE";

  private final NamedParameterJdbcTemplate jdbc;

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp timestamp = rs.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  /**
   * Inserts a new active connector binding unless the connector DID is already present.
   *
   * <p>If a row already exists, the current row is returned. Callers must perform authorization
   * before invoking this method because the database conflict handling is purely idempotent.
   *
   * @param connectorDid connector DID to bind
   * @param holderDid verified participant holder DID
   * @param membershipIssuer trusted issuer of the membership credential
   * @param presentationId verified presentation identifier
   * @param challengeId consumed challenge identifier
   * @param consumer whether the binding holder has consumer permissions
   * @param provider whether the binding holder has provider permissions
   * @return inserted or existing binding row
   */
  public ConnectorBinding insertIfAbsent(String connectorDid, String holderDid,
      String membershipIssuer, String presentationId, String challengeId,
      boolean consumer, boolean provider) {
    Instant now = Instant.now();
    jdbc.update("""
        INSERT INTO oid4vp_connector_binding
          (id, connector_did, holder_did, membership_issuer, presentation_id, challenge_id,
           bound_at, binding_status, is_consumer, is_provider)
        VALUES
          (:id, :connectorDid, :holderDid, :membershipIssuer, :presentationId, :challengeId,
           :boundAt, :bindingStatus, :consumer, :provider)
        ON CONFLICT (connector_did) DO NOTHING
        """, new MapSqlParameterSource()
        .addValue("id", UUID.randomUUID())
        .addValue("connectorDid", connectorDid)
        .addValue("holderDid", holderDid)
        .addValue("membershipIssuer", membershipIssuer)
        .addValue("presentationId", presentationId)
        .addValue("challengeId", challengeId)
        .addValue("boundAt", Timestamp.from(now))
        .addValue("bindingStatus", STATUS_ACTIVE)
        .addValue("consumer", consumer)
        .addValue("provider", provider));

    return jdbc.queryForObject("""
        SELECT id, connector_did, holder_did, membership_issuer, presentation_id, challenge_id,
               bound_at, binding_status, is_consumer, is_provider
          FROM oid4vp_connector_binding
         WHERE connector_did = :connectorDid
        """, Map.of("connectorDid", connectorDid), this::mapBinding);
  }

  /**
   * Finds the active binding for a connector DID.
   *
   * @param connectorDid connector DID to look up
   * @return active binding if present
   */
  public Optional<ConnectorBinding> findActiveByConnectorDid(String connectorDid) {
    try {
      return Optional.ofNullable(jdbc.queryForObject("""
          SELECT id, connector_did, holder_did, membership_issuer, presentation_id, challenge_id,
                 bound_at, binding_status, is_consumer, is_provider
            FROM oid4vp_connector_binding
           WHERE connector_did = :connectorDid
             AND binding_status = :bindingStatus
          """, Map.of(
          "connectorDid", connectorDid,
          "bindingStatus", STATUS_ACTIVE), this::mapBinding));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  /**
   * Maps one connector binding database row to the service-layer record.
   *
   * @param rs current result set row
   * @param rowNum row number supplied by Spring JDBC
   * @return connector binding record
   * @throws SQLException if a column cannot be read
   */
  private ConnectorBinding mapBinding(ResultSet rs, int rowNum) throws SQLException {
    return new ConnectorBinding(
        rs.getString("id"),
        rs.getString("connector_did"),
        rs.getString("holder_did"),
        rs.getString("membership_issuer"),
        rs.getString("presentation_id"),
        rs.getString("challenge_id"),
        instant(rs, "bound_at"),
        rs.getString("binding_status"),
        rs.getBoolean("is_consumer"),
        rs.getBoolean("is_provider"));
  }
}

