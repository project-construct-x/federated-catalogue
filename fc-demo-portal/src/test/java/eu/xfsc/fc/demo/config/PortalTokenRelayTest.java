package eu.xfsc.fc.demo.config;

/*-
 * ---license-start
 * fc-demo-portal
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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.xfsc.fc.client.UserClient;
import eu.xfsc.fc.demo.controller.UsersController;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import reactor.core.publisher.Mono;

/** Real portal security, session repository, authorized-client manager and UserClient.
 * Only the HTTP transport is replaced; no Keycloak login or downstream server is simulated.
 */
@SpringJUnitWebConfig(PortalTokenRelayTest.Config.class)
class PortalTokenRelayTest {
  private static final String CLIENT = "federated-catalogue";
  @Autowired WebApplicationContext context;
  @Autowired ClientRegistrationRepository registrations;
  @Autowired HttpSessionOAuth2AuthorizedClientRepository authorizedClients;
  @Autowired RecordingTransport transport;
  private MockMvc mvc;

  @BeforeEach void setup() {
    transport.requests.clear();
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test void forwardsAccessTokenFromAdminSessionWithoutForwardingIdTokenOrCookie() throws Exception {
    var session = login(true, "admin-access-token");
    mvc.perform(get("/users").session(session)).andExpect(status().isOk());
    assertThat(transport.requests).hasSize(1);
    var request = transport.requests.getFirst();
    assertThat(request.url().getPath()).isEqualTo("/users");
    assertThat(request.headers().get(HttpHeaders.AUTHORIZATION)).containsExactly("Bearer admin-access-token");
    assertThat(request.headers().get(HttpHeaders.COOKIE)).isNull();
  }

  @Test void separateSessionsNeverReuseAnotherAdminsAccessToken() throws Exception {
    mvc.perform(get("/users").session(login(true, "first-admin-token"))).andExpect(status().isOk());
    mvc.perform(get("/users").session(login(true, "second-admin-token"))).andExpect(status().isOk());
    assertThat(transport.requests).extracting(r -> r.headers().getFirst(HttpHeaders.AUTHORIZATION))
        .containsExactly("Bearer first-admin-token", "Bearer second-admin-token");
  }

  @Test void nonAdminSessionIsRejectedBeforeCallingServer() throws Exception {
    mvc.perform(get("/users").session(login(false, "member-access-token"))).andExpect(status().isForbidden());
    assertThat(transport.requests).isEmpty();
  }

  @Test void missingSessionRedirectsToLoginWithoutCallingServer() throws Exception {
    mvc.perform(get("/users")).andExpect(status().is3xxRedirection());
    assertThat(transport.requests).isEmpty();
  }

  private MockHttpSession login(boolean admin, String accessToken) {
    var now = Instant.now();
    var authorities = new CustomOidcUserService().extractAdminRole(Map.of("resource_access",
        Map.of(CLIENT, Map.of("roles", admin ? List.of("ADMIN_ALL") : List.of("ASSET_READ")))), CLIENT);
    var principal = new DefaultOidcUser(authorities,
        new OidcIdToken("id-token-must-not-be-forwarded", now, now.plusSeconds(600), Map.of("sub", accessToken)));
    var authentication = new OAuth2AuthenticationToken(principal, authorities, "fc-client-oidc");
    var session = new MockHttpSession();
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(authentication));
    var request = new MockHttpServletRequest();
    request.setSession(session);
    authorizedClients.saveAuthorizedClient(new OAuth2AuthorizedClient(
        registrations.findByRegistrationId("fc-client-oidc"), principal.getName(),
        new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, accessToken, now, now.plusSeconds(600))),
        authentication, request, new MockHttpServletResponse());
    return session;
  }

  static class RecordingTransport {
    final List<ClientRequest> requests = new CopyOnWriteArrayList<>();
    Mono<ClientResponse> exchange(ClientRequest request) {
      requests.add(request);
      return Mono.just(ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, "application/json")
          .body("{\"totalCount\":0,\"items\":[]}").build());
    }
  }

  @Configuration @EnableWebMvc
  @Import({SecurityConfig.class, CustomOidcUserService.class, UsersController.class})
  static class Config {
    @Bean ClientRegistrationRepository registrations() {
      return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("fc-client-oidc")
          .clientId(CLIENT).clientSecret("test-secret").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
          .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}").scope("openid")
          .authorizationUri("https://issuer.example/authorize").tokenUri("https://issuer.example/token")
          .jwkSetUri("https://issuer.example/certs").build());
    }
    @Bean HttpSessionOAuth2AuthorizedClientRepository authorizedClients() {
      return new HttpSessionOAuth2AuthorizedClientRepository();
    }
    @Bean RecordingTransport transport() { return new RecordingTransport(); }
    @Bean UserClient userClient(ClientRegistrationRepository registrations,
        HttpSessionOAuth2AuthorizedClientRepository repository, RecordingTransport transport) {
      var config = new ClientConfig();
      var manager = config.authorizedClientManager(registrations, repository);
      var client = config.webClient(manager, "https://catalogue.example").mutate()
          .exchangeFunction(transport::exchange).build();
      return config.userClient("https://catalogue.example", client);
    }
  }
}
