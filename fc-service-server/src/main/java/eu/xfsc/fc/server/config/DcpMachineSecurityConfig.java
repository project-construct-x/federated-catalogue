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

import eu.xfsc.fc.core.security.DcpAuthenticationToken;
import eu.xfsc.fc.core.service.dcp.DcpMachineAuthenticationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/** Incoming DCP authentication for every explicitly classified machine operation. */
@Configuration
public class DcpMachineSecurityConfig {

  /** Authenticates each request independently of browser sessions and Keycloak roles. */
  @Bean
  @Order(0)
  public SecurityFilterChain dcpMachineChain(HttpSecurity http, DcpMachineAuthenticationService service)
      throws Exception {
    return http.securityMatcher(DcpMachineEndpoints.MATCHER)
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(cache -> cache.disable())
        .addFilterBefore(new DcpMachineAuthenticationFilter(service), AnonymousAuthenticationFilter.class)
        .authorizeHttpRequests(auth -> auth.anyRequest().access((authentication, context) ->
            new AuthorizationDecision(authentication.get() instanceof DcpAuthenticationToken
                && authentication.get().isAuthenticated())))
        .build();
  }
}
