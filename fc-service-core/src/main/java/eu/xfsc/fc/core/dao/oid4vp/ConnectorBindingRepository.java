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
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC repository for connector-to-participant bindings created after OID4VP bootstrap. */
@Repository
@RequiredArgsConstructor
public class ConnectorBindingRepository {

  private static final String STATUS_ACTIVE = "ACTIVE";

  private final NamedParameterJdbcTemplate jdbc;

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
        .addValue("boundAt", now)
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

  private ConnectorBinding mapBinding(ResultSet rs, int rowNum) throws SQLException {
    return new ConnectorBinding(
        rs.getString("id"),
        rs.getString("connector_did"),
        rs.getString("holder_did"),
        rs.getString("membership_issuer"),
        rs.getString("presentation_id"),
        rs.getString("challenge_id"),
        rs.getObject("bound_at", Instant.class),
        rs.getString("binding_status"),
        rs.getBoolean("is_consumer"),
        rs.getBoolean("is_provider"));
  }
}

