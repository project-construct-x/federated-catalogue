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
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
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
  public SecurityFilterChain oid4vpFilterChain(HttpSecurity http) throws Exception {
    http
      .securityMatcher(
        "/api/auth/oid4vp/**"
      )
      .authorizeHttpRequests(authorization -> authorization
        .anyRequest().authenticated()
      )
      .exceptionHandling(c -> c.accessDeniedHandler(accessDeniedHandler()));
      // TODO: Implement OID4VP authentication
      return http.build();
  }

  @Bean
  @Order(2)
  public SecurityFilterChain dcpFilterChain(HttpSecurity http) throws Exception {
    http
      .securityMatcher(
        "/verification",
        "/dcp/**",
        "/assets",
        "/assets/**",
        "/trust-frameworks",
        "/validations/**",
        "/participants",
        "/participants/**"
      )
      .authorizeHttpRequests(authorization -> authorization
        // .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
        // .requestMatchers(HttpMethod.GET, "/swagger-ui/**").permitAll()
        // .requestMatchers(HttpMethod.GET, "/actuator", "/actuator/**").permitAll()
        // .requestMatchers(HttpMethod.GET, "/js/**", "/css/**").permitAll()

        // Verification APIs
        .requestMatchers("/verification").authenticated()

        // DCP verifier pull — auth is the client Self-Issued ID Token (not Keycloak)
        .requestMatchers(HttpMethod.POST, "/dcp/presentations").permitAll()
        
        // Asset APIs
        .requestMatchers(HttpMethod.PUT, "/assets/*").authenticated()
        .requestMatchers(HttpMethod.POST, "/assets/*/versions/*/revoke").authenticated()
        .requestMatchers(HttpMethod.GET, "/assets/*/versions").authenticated()
        .requestMatchers(HttpMethod.POST, "/assets/*/revoke").authenticated()
        // Asset-linking sub-resource endpoints — must appear before the broader /assets/* GET matcher
        .requestMatchers(HttpMethod.POST, "/assets/*/human-readable").authenticated()
        .requestMatchers(HttpMethod.PUT, "/assets/*/human-readable").authenticated()
        .requestMatchers(HttpMethod.GET, "/assets/*/human-readable").authenticated()
        .requestMatchers(HttpMethod.GET, "/assets/*/machine-readable").authenticated()
        .requestMatchers(HttpMethod.GET, "/assets/*/validations").authenticated()
        .requestMatchers(HttpMethod.POST, "/assets/validate").authenticated()
        .requestMatchers(HttpMethod.POST, "/assets/*/provenance").authenticated()
        .requestMatchers(HttpMethod.GET, "/assets/*/provenance", "/assets/*/provenance/*").authenticated()
        .requestMatchers(HttpMethod.POST, "/assets/*/provenance/*/verify", "/assets/*/provenance/verify").authenticated()
        .requestMatchers(HttpMethod.GET, "/assets", "/assets/*").authenticated()
        .requestMatchers(HttpMethod.POST, "/assets").authenticated()
        .requestMatchers(HttpMethod.DELETE, "/assets/*").authenticated()
        .requestMatchers(HttpMethod.DELETE, "/assets/by-id/**").authenticated()

        // Compliance check APIs
        .requestMatchers(HttpMethod.POST, "/assets/*/compliance-check").authenticated()
        .requestMatchers(HttpMethod.GET, "/assets/*/compliance-checks").authenticated()
        .requestMatchers(HttpMethod.GET, "/trust-frameworks").authenticated()

        // Validation result read APIs
        .requestMatchers(HttpMethod.GET, "/validations/**").authenticated()

        .requestMatchers(HttpMethod.POST, "/participants").authenticated()
        .requestMatchers(HttpMethod.GET, "/participants").authenticated()
        .requestMatchers(HttpMethod.PUT, "/participants/*").authenticated()
        .requestMatchers(HttpMethod.DELETE, "/participants/*").authenticated()
        .requestMatchers(HttpMethod.GET, "/participants/*").authenticated()

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
        "/session",
        "/query", 
        "/query/**"
      )
      .authorizeHttpRequests(authorization -> authorization
        .anyRequest().hasRole(ADMIN_ALL)
      )
      .exceptionHandling(c -> c.accessDeniedHandler(accessDeniedHandler()))
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
