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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Real JWT verification and admin security chain; probes exclude the downstream Keycloak Admin API.
 * The local public key replaces JWKS discovery, while the standard issuer/time validators remain active.
 */
@SpringJUnitWebConfig(AdminJwtAuthenticationTest.Config.class)
@TestPropertySource(properties = "keycloak.resource=federated-catalogue")
class AdminJwtAuthenticationTest {
  private static final String ISSUER = "https://keycloak.example/realms/catalogue";
  @Autowired WebApplicationContext context;
  @Autowired RSAKey signingKey;
  private MockMvc mvc;

  @BeforeEach void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @ParameterizedTest @ValueSource(strings = {"/admin/me", "/users"})
  void validClientAdminTokenReachesHandler(String path) throws Exception {
    mvc.perform(get(path).header("Authorization", "Bearer " + token(signingKey, ISSUER, 600,
        Map.of("resource_access", Map.of("federated-catalogue", Map.of("roles", List.of("ADMIN_ALL")))))))
        .andExpect(status().isOk());
  }

  @ParameterizedTest @ValueSource(strings = {"/admin/me", "/users"})
  void adminRoleInWrongClientOrRealmIsForbidden(String path) throws Exception {
    for (var claims : List.of(
        Map.<String, Object>of("resource_access", Map.of("demo-portal", Map.of("roles", List.of("ADMIN_ALL")))),
        Map.<String, Object>of("realm_access", Map.of("roles", List.of("ADMIN_ALL"))))) {
      mvc.perform(get(path).header("Authorization", "Bearer " + token(signingKey, ISSUER, 600, claims)))
          .andExpect(status().isForbidden());
    }
  }

  @ParameterizedTest @ValueSource(strings = {"/admin/me", "/users"})
  void invalidTokensAreUnauthorizedEvenWithAdminRole(String path) throws Exception {
    var claims = Map.<String, Object>of("resource_access",
        Map.of("federated-catalogue", Map.of("roles", List.of("ADMIN_ALL"))));
    var untrustedKey = new RSAKeyGenerator(2048).generate();
    for (String token : List.of(token(signingKey, "https://other-issuer.example", 600, claims),
        token(signingKey, ISSUER, -300, claims), token(untrustedKey, ISSUER, 600, claims))) {
      mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }
    mvc.perform(get(path)).andExpect(status().isUnauthorized());
  }

  @ParameterizedTest @ValueSource(strings = {"/admin/me", "/users", "/roles"})
  void keycloakServiceAccountPermissionsDoNotGrantCatalogueAdminAccess(String path) throws Exception {
    var claims = Map.<String, Object>of("resource_access", Map.of("realm-management",
        Map.of("roles", List.of("manage-users", "view-clients"))));
    mvc.perform(get(path).header("Authorization", "Bearer " + token(signingKey, ISSUER, 600, claims)))
        .andExpect(status().isForbidden());
  }

  private String token(RSAKey key, String issuer, long expiresIn, Map<String, Object> claims) throws Exception {
    var builder = new JWTClaimsSet.Builder().issuer(issuer).subject("admin")
        .issueTime(Date.from(Instant.now().minusSeconds(600)))
        .expirationTime(Date.from(Instant.now().plusSeconds(expiresIn)));
    claims.forEach(builder::claim);
    var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  @Configuration @EnableWebMvc @Import(SecurityConfig.class)
  static class Config {
    @Bean RSAKey signingKey() throws Exception { return new RSAKeyGenerator(2048).generate(); }
    @Bean JwtDecoder decoder(RSAKey key) throws Exception {
      var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
      decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
      return decoder;
    }
    @Bean Probe probe() { return new Probe(); }
  }

  @RestController static class Probe {
    @GetMapping({"/admin/me", "/users"}) String reached() { return "reached"; }
  }
}
