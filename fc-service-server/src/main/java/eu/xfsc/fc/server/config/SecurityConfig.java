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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import eu.xfsc.fc.api.generated.model.Error;
import eu.xfsc.fc.core.service.oid4vp.BootstrapTokenService;
import eu.xfsc.fc.core.security.DcpAuthenticationToken;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static eu.xfsc.fc.server.util.CommonConstants.ADMIN_ALL;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Note: WebSecurity adapter is deprecated in spring security 5.7;
 * so we are using SecurityFilterChain for configuration security without extending deprecated adapter.
 */
@Configuration
@EnableWebSecurity //(debug = true)
//@EnableMethodSecurity
public class SecurityConfig {
  private static final ObjectMapper mapper = new ObjectMapper();

  private static final String COMMON_FORBIDDEN_ERROR_MESSAGE = "User does not have permission to execute this request.";

  private static final AuthorizationManager<RequestAuthorizationContext> DCP_ONLY = (authentication, context) -> {
    var caller = authentication.get();
    return new AuthorizationDecision(caller instanceof DcpAuthenticationToken && caller.isAuthenticated());
  };

  private final String resourceId;

  public SecurityConfig(@Value("${keycloak.resource}") String resourceId) {
    this.resourceId = resourceId;
  }

  /** Public documentation, static resources and orchestrator health probes only. */
  @Bean
  @Order(-1)
  public SecurityFilterChain publicResourcesFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/actuator/health", "/actuator/health/**", "/api/docs", "/api/docs.yaml",
            "/api/docs/**", "/swagger-ui/**", "/js/**", "/css/**")
        .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, "/**").permitAll()
            .anyRequest().denyAll());
    return http.build();
  }

  /**
   * Define security constraints for the application resources.
   */
  @Bean
  @Order(-2)
  public SecurityFilterChain optionsFilterChain(HttpSecurity http) throws Exception {
    http
      .securityMatcher(request ->
            HttpMethod.OPTIONS.matches(request.getMethod()))
        .authorizeHttpRequests(auth ->
            auth.anyRequest().permitAll())
      .exceptionHandling(c -> c.accessDeniedHandler(accessDeniedHandler()));
      return http.build();
  }

  @Bean
  @Order(1)
  public SecurityFilterChain oid4vpFilterChain(HttpSecurity http, BootstrapTokenService tokens) throws Exception {
     http.securityMatcher("/api/auth/oid4vp/**")
              .csrf(c -> c.disable())
              .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
              .authorizeHttpRequests(a -> a
                      .requestMatchers(HttpMethod.POST, "/api/auth/oid4vp/requests",
                              "/api/auth/oid4vp/direct-post", "/api/auth/oid4vp/token").permitAll()
                      .requestMatchers(HttpMethod.POST, "/api/auth/oid4vp/connectors/*/bind").hasAuthority("SCOPE_connector:bind")
                      .anyRequest().denyAll())
              .addFilterBefore(new BootstrapTokenAuthenticationFilter(tokens), AuthorizationFilter.class)
              .exceptionHandling(c -> c.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                      .accessDeniedHandler(accessDeniedHandler()));
      return http.build();

  @Bean
  @Order(2)
  public SecurityFilterChain dcpFilterChain(HttpSecurity http) throws Exception {
    http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(cache -> cache.disable())
        .csrf(csrf -> csrf.disable());
    http
      .securityMatcher(
        "/verification",
        "/dcp/**",
        "/assets",
        "/assets/**",
        "/trust-frameworks",
        "/validations/**",
        "/participants",
        "/participants/**",
        "/query",
        "/query/**"
      )
      .authorizeHttpRequests(authorization -> authorization

        // Query/discovery is a machine data API, not an administrative capability.
        .requestMatchers(HttpMethod.POST, "/query", "/query/search").access(DCP_ONLY)
        .requestMatchers(HttpMethod.GET, "/query", "/query/info").access(DCP_ONLY)

        // Verification APIs
        .requestMatchers("/verification").access(DCP_ONLY)

        // DCP verifier pull — auth is the client Self-Issued ID Token (not Keycloak)
        .requestMatchers(HttpMethod.POST, "/dcp/presentations").permitAll()
        
        // Asset APIs
        .requestMatchers(HttpMethod.PUT, "/assets/*").access(DCP_ONLY)
        .requestMatchers(HttpMethod.POST, "/assets/*/versions/*/revoke").access(DCP_ONLY)
        .requestMatchers(HttpMethod.GET, "/assets/*/versions").access(DCP_ONLY)
        .requestMatchers(HttpMethod.POST, "/assets/*/revoke").access(DCP_ONLY)
        // Asset-linking sub-resource endpoints — must appear before the broader /assets/* GET matcher
        .requestMatchers(HttpMethod.POST, "/assets/*/human-readable").access(DCP_ONLY)
        .requestMatchers(HttpMethod.PUT, "/assets/*/human-readable").access(DCP_ONLY)
        .requestMatchers(HttpMethod.GET, "/assets/*/human-readable").access(DCP_ONLY)
        .requestMatchers(HttpMethod.GET, "/assets/*/machine-readable").access(DCP_ONLY)
        .requestMatchers(HttpMethod.GET, "/assets/*/validations").access(DCP_ONLY)
        .requestMatchers(HttpMethod.POST, "/assets/validate").access(DCP_ONLY)
        .requestMatchers(HttpMethod.POST, "/assets/*/provenance").access(DCP_ONLY)
        .requestMatchers(HttpMethod.GET, "/assets/*/provenance", "/assets/*/provenance/*").access(DCP_ONLY)
        .requestMatchers(HttpMethod.POST, "/assets/*/provenance/*/verify", "/assets/*/provenance/verify").access(DCP_ONLY)
        .requestMatchers(HttpMethod.GET, "/assets", "/assets/*").access(DCP_ONLY)
        .requestMatchers(HttpMethod.POST, "/assets").access(DCP_ONLY)
        .requestMatchers(HttpMethod.DELETE, "/assets/*").access(DCP_ONLY)
        .requestMatchers(HttpMethod.DELETE, "/assets/by-id/**").access(DCP_ONLY)

        // Compliance check APIs
        .requestMatchers(HttpMethod.POST, "/assets/*/compliance-check").access(DCP_ONLY)
        .requestMatchers(HttpMethod.GET, "/assets/*/compliance-checks").access(DCP_ONLY)
        .requestMatchers(HttpMethod.GET, "/trust-frameworks").access(DCP_ONLY)

        // Validation result read APIs
        .requestMatchers(HttpMethod.GET, "/validations/**").access(DCP_ONLY)

        .requestMatchers(HttpMethod.POST, "/participants").access(DCP_ONLY)
        .requestMatchers(HttpMethod.GET, "/participants").access(DCP_ONLY)
        .requestMatchers(HttpMethod.PUT, "/participants/*").access(DCP_ONLY)
        .requestMatchers(HttpMethod.DELETE, "/participants/*").access(DCP_ONLY)
        .requestMatchers(HttpMethod.GET, "/participants/*").access(DCP_ONLY)

        .anyRequest().denyAll()
      )  
      .exceptionHandling(c -> c
          .authenticationEntryPoint(
              new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
          .accessDeniedHandler(accessDeniedHandler())
      );
      // TODO: Implement DCP authentication
      return http.build();
  }

  @Bean
  @Order(1)
  public SecurityFilterChain adminFilterChain(HttpSecurity http) throws Exception {
    http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(cache -> cache.disable())
        .csrf(csrf -> csrf.disable());
    http
      .securityMatcher(
        "/admin/**", 
        "/actuator",
        "/actuator/**",
        "/schemas", 
        "/schemas/**",
        "/users",
        "/users/**",
        "/participants/*/users",
        "/roles",
        "/session"
      )
      .authorizeHttpRequests(authorization -> authorization
        .anyRequest()
      .hasRole(ADMIN_ALL)
      ).addFilterBefore(new BootstrapTokenRejectingFilter(), BearerTokenAuthenticationFilter.class)
            .access((authentication, context) -> {
                var caller = authentication.get();
                return new AuthorizationDecision(caller instanceof JwtAuthenticationToken && caller.isAuthenticated()
                        && caller.getAuthorities().stream().anyMatch(role -> ("ROLE_" + ADMIN_ALL).equals(role.getAuthority())));
            })
      ).exceptionHandling(c -> c.accessDeniedHandler(accessDeniedHandler()))
      .oauth2ResourceServer(c -> c
          .jwt(jc -> jc.jwtAuthenticationConverter(new CustomJwtAuthenticationConverter(resourceId))));
      return http.build();
  }

  /** Unassigned paths must not bypass Spring Security when using separate chains. */
  @Bean
  @Order(4)
  public SecurityFilterChain fallbackFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
        .exceptionHandling(c -> c
            .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            .accessDeniedHandler(accessDeniedHandler()));
    return http.build();
  }

  /**
   * Customize Access Denied application exception.
   */
  private static AccessDeniedHandler accessDeniedHandler() {
    return (HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) -> {
      response.setStatus(HttpStatus.FORBIDDEN.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      Error forbiddenError =
          new Error("forbidden_error", accessDeniedException.getMessage().contains("Access is denied")
              ? accessDeniedException.getMessage() : COMMON_FORBIDDEN_ERROR_MESSAGE);
      ObjectWriter ow = mapper.writer().withDefaultPrettyPrinter();
      response.getWriter().write(ow.writeValueAsString(forbiddenError));
    };
  }
}
