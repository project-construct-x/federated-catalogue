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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards the shipped realm contract required by KeycloakConfig and UserDaoImpl. */
class KeycloakRealmConfigurationTest {
  @ParameterizedTest
  @ValueSource(strings = {"dev", "staging", "prod"})
  void adminApiServiceAccountHasOnlyTheRequiredTechnicalRoles(String environment) throws Exception {
    Path root = repositoryRoot("keycloak/realms");
    JsonNode realm = new ObjectMapper().readTree(root.resolve("keycloak/realms")
        .resolve(environment).resolve("fc-realm.json").toFile());
    assertAdminOnlyRealm(realm);
  }

  @Test
  void helmRealmMatchesTheAdminOnlyContract() throws Exception {
    Path root = repositoryRoot("deployment/helm/fc-service/fc-realm.json");
    JsonNode realm = new ObjectMapper().readTree(
        root.resolve("deployment/helm/fc-service/fc-realm.json").toFile());
    assertAdminOnlyRealm(realm);
  }

  private static Path repositoryRoot(String marker) {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !root.resolve(marker).toFile().exists()) {
      root = root.getParent();
    }
    assertThat(root).as("repository root containing " + marker).isNotNull();
    return root;
  }

  private static void assertAdminOnlyRealm(JsonNode realm) {
    List<JsonNode> clients = new ArrayList<>();
    realm.path("clients").forEach(client -> {
      if (client.path("clientId").asText().equals("federated-catalogue")) {
        clients.add(client);
      }
    });
    assertThat(clients).hasSize(1);
    JsonNode client = clients.getFirst();
    assertThat(client.path("serviceAccountsEnabled").asBoolean()).isTrue();
    assertThat(client.path("publicClient").asBoolean()).isFalse();
    assertThat(client.path("directAccessGrantsEnabled").asBoolean()).isFalse();
    assertThat(client.path("authorizationServicesEnabled").asBoolean()).isFalse();
    assertThat(client.path("standardFlowEnabled").asBoolean()).isTrue();
    assertThat(client.path("fullScopeAllowed").asBoolean()).isTrue();

    List<JsonNode> accounts = new ArrayList<>();
    realm.path("users").forEach(user -> {
      if (user.path("serviceAccountClientId").asText().equals("federated-catalogue")) {
        accounts.add(user);
      }
    });
    assertThat(accounts).hasSize(1);
    JsonNode account = accounts.getFirst();
    assertThat(account.path("enabled").asBoolean()).isTrue();
    assertThat(account.path("credentials").size()).isZero();
    assertThat(account.path("realmRoles").size()).isZero();
    assertThat(account.path("clientRoles").size()).isEqualTo(1);
    List<String> roles = new ArrayList<>();
    account.path("clientRoles").path("realm-management").forEach(role -> roles.add(role.asText()));
    assertThat(roles).containsExactlyInAnyOrder("manage-users", "view-clients");
    List<String> applicationRoles = new ArrayList<>();
    realm.path("roles").path("client").path("federated-catalogue")
        .forEach(role -> applicationRoles.add(role.path("name").asText()));
    assertThat(applicationRoles).containsExactly("ADMIN_ALL");
  }
}
