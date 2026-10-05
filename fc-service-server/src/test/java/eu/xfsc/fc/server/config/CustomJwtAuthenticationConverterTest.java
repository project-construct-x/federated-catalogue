package eu.xfsc.fc.server.config;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Only the configured client's explicit ADMIN_ALL role grants admin authority. */
class CustomJwtAuthenticationConverterTest {
  private static final String CLIENT = "federated-catalogue";
  private final CustomJwtAuthenticationConverter converter = new CustomJwtAuthenticationConverter(CLIENT);

  private Set<String> authorities(Map<String, Object> claims) {
    var builder = Jwt.withTokenValue("test").header("alg", "RS256").subject("admin");
    claims.forEach(builder::claim);
    return converter.convert(builder.build()).getAuthorities().stream()
        .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
  }

  @Test void explicitClientAdminIsTheOnlyAuthority() {
    assertEquals(Set.of("ROLE_ADMIN_ALL"), authorities(Map.of("resource_access",
        Map.of(CLIENT, Map.of("roles", List.of("ADMIN_ALL", "ASSET_CREATE", "Ro-MU-CA"))),
        "scope", "openid ADMIN_ALL", "roles", List.of("gaia-x-admin"))));
  }

  @ParameterizedTest
  @ValueSource(strings = {"gaia-x-admin", "gaia-x-notar", "gaia-x-business-owner", "ADMIN_ALL"})
  void legacyRolesNeverGrantAdmin(String role) {
    assertTrue(authorities(Map.of("roles", List.of(role),
        "realm_access", Map.of("roles", List.of(role)))).isEmpty());
  }

  @Test void otherClientAdminDoesNotGrantAccess() {
    assertTrue(authorities(Map.of("resource_access", Map.of("other", Map.of("roles", List.of("ADMIN_ALL"))),
        "roles", List.of("gaia-x-admin"))).isEmpty());
  }

  @Test void fineGrainedRolesAndScopesAreIgnored() {
    assertTrue(authorities(Map.of("resource_access", Map.of(CLIENT, Map.of("roles",
        List.of("ASSET_CREATE", "SCHEMA_CREATE", "QUERY_EXECUTE", "Ro-MU-CA"))),
        "scope", "ADMIN_ALL")).isEmpty());
  }

  @Test void missingOrMalformedClaimsFailClosed() {
    for (Map<String, Object> claims : List.<Map<String, Object>>of(Map.of(),
        Map.of("resource_access", "invalid"), Map.of("resource_access", Map.of(CLIENT, "invalid")),
        Map.of("resource_access", Map.of(CLIENT, Map.of("roles", "ADMIN_ALL"))),
        Map.of("resource_access", Map.of(CLIENT, Map.of("roles", List.of(42)))))) {
      assertTrue(authorities(claims).isEmpty());
    }
  }
}