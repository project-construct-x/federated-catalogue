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

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.xfsc.fc.core.security.DcpAuthenticationToken;
import eu.xfsc.fc.core.security.DcpIdentity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Starts all base security chains; probe handlers isolate authorization from business/storage code. */
@SpringJUnitWebConfig(KeycloakBoundaryTest.Config.class)
@TestPropertySource(properties = "keycloak.resource=federated-catalogue")
class KeycloakBoundaryTest {
  @Autowired WebApplicationContext context;
  @Autowired JwtDecoder decoder;
  MockMvc mvc;

  private Jwt adminJwt() {
    return Jwt.withTokenValue("admin").header("alg", "RS256").subject("admin")
        .claim("resource_access", Map.of("federated-catalogue", Map.of("roles", List.of("ADMIN_ALL"))))
        .build();
  }

  private DcpAuthenticationToken dcp() {
    return new DcpAuthenticationToken(new DcpIdentity("did:web:participant.example", null));
  }

  @BeforeEach void setup() {
    reset(decoder);
    when(decoder.decode("admin")).thenReturn(adminJwt());
    when(decoder.decode("invalid")).thenThrow(new BadJwtException("Invalid signature"));
    when(decoder.decode("member")).thenReturn(Jwt.withTokenValue("member").header("alg", "RS256")
        .subject("member").claim("roles", List.of("gaia-x-notar")).build());
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @ParameterizedTest
  @CsvSource({"GET,/admin/me", "POST,/admin/graph/rebuild", "GET,/actuator", "GET,/users",
      "POST,/users", "GET,/roles", "GET,/session", "POST,/schemas", "GET,/schemas/latest",
      "GET,/participants/participant/users"})
  void adminEndpointsRequireKeycloakAdmin(String method, String path) throws Exception {
    var verb = HttpMethod.valueOf(method);
    mvc.perform(request(verb, path).header("Authorization", "Bearer admin")).andExpect(status().isOk());
    mvc.perform(request(verb, path)).andExpect(status().isUnauthorized());
    mvc.perform(request(verb, path).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
    mvc.perform(request(verb, path).header("Authorization", "Bearer member")).andExpect(status().isForbidden());
    mvc.perform(request(verb, path).with(authentication(dcp()))).andExpect(status().isForbidden());
  }

  @ParameterizedTest
  @CsvSource({"GET,/query", "POST,/query", "POST,/query/search", "GET,/query/info", "GET,/assets", "POST,/assets",
      "PUT,/assets/a", "DELETE,/assets/a", "POST,/assets/a/revoke", "POST,/assets/a/versions/1/revoke",
      "GET,/assets/a/versions", "POST,/assets/a/human-readable", "PUT,/assets/a/human-readable",
      "GET,/assets/a/human-readable", "GET,/assets/a/machine-readable", "GET,/assets/a/validations",
      "POST,/assets/validate", "POST,/assets/a/provenance", "GET,/assets/a/provenance",
      "POST,/assets/a/provenance/verify", "POST,/assets/a/compliance-check", "GET,/assets/a/compliance-checks",
      "DELETE,/assets/by-id/a", "GET,/trust-frameworks", "GET,/validations/1", "POST,/verification",
      "GET,/participants", "POST,/participants", "PUT,/participants/a", "DELETE,/participants/a", "GET,/participants/a"})
  void dataEndpointsRequireDcpWithoutAnyKeycloakRole(String method, String path) throws Exception {
    var verb = HttpMethod.valueOf(method);
    mvc.perform(request(verb, path).with(authentication(dcp()))).andExpect(status().isOk());
    mvc.perform(request(verb, path)).andExpect(status().isUnauthorized());
    mvc.perform(request(verb, path).header("Authorization", "Bearer admin")).andExpect(status().isUnauthorized());
    mvc.perform(request(verb, path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN_ALL"))))
        .andExpect(status().isForbidden());
    mvc.perform(request(verb, path).with(user("user").roles("ASSET_CREATE", "QUERY_EXECUTE", "SCHEMA_CREATE", "Ro-MU-CA")))
        .andExpect(status().isForbidden());
    verifyNoInteractions(decoder);
  }

  @Test void sessionsCannotAuthenticateAdminDataOrBootstrapRequests() throws Exception {
    var session = new MockHttpSession();
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(new CustomJwtAuthenticationConverter("federated-catalogue").convert(adminJwt())));
    mvc.perform(get("/admin/me").session(session)).andExpect(status().isUnauthorized());
    mvc.perform(post("/query").session(session)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/oid4vp/bootstrap").session(session)).andExpect(status().is4xxClientError());
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(dcp()));
    mvc.perform(get("/assets").session(session)).andExpect(status().isUnauthorized());
  }

  @Test void forgedOrUnauthenticatedDcpAndBootstrapJwtCannotReachData() throws Exception {
    var fake = UsernamePasswordAuthenticationToken.authenticated(dcp().getPrincipal(), null, List.of());
    mvc.perform(post("/query").with(authentication(fake))).andExpect(status().isForbidden());
    var unverified = dcp();
    unverified.setAuthenticated(false);
    mvc.perform(post("/query").with(authentication(unverified))).andExpect(status().isForbidden());
    mvc.perform(post("/query").with(jwt().jwt(j -> j.claim("purpose", "connector-bootstrap"))))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/auth/oid4vp/bootstrap").with(authentication(dcp()))).andExpect(status().isForbidden());
  }

  @Test void publicResourcesAndProtocolBoundaryRemainSeparateFromUnknownPaths() throws Exception {
    mvc.perform(get("/api/docs")).andExpect(status().isOk());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    // The real presentation controller validates its Self-Issued ID Token, not the Keycloak chain.
    mvc.perform(post("/dcp/presentations")).andExpect(status().isOk());
    mvc.perform(post("/unknown").with(csrf()).with(authentication(dcp()))).andExpect(status().isForbidden());
    mvc.perform(post("/query/unknown").with(authentication(dcp()))).andExpect(status().isForbidden());
  }

  @Configuration @EnableWebMvc @Import(SecurityConfig.class)
  static class Config {
    @Bean JwtDecoder decoder() { return mock(JwtDecoder.class); }
    @Bean ProbeController probe() { return new ProbeController(); }
  }

  @RestController
  static class ProbeController {
    @RequestMapping("/**") String probe() { return "reached"; }
  }
}
