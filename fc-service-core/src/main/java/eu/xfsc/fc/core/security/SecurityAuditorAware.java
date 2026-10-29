package eu.xfsc.fc.core.security;

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

import java.util.Optional;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Provides the DCP participant DID or the current JWT subject as the Spring Data JPA auditor.
 * Returns empty when there is no active security context (background jobs, tests).
 */
@Component("securityAuditorAware")
public class SecurityAuditorAware implements AuditorAware<String> {

  /**
   * Returns the membership subject DID for authenticated DCP requests, or the JWT subject
   * for existing JWT authentication (including Keycloak administrators). The optional DCP
   * actor DID does not replace the participant identity. A DCP identity inside another token
   * type is not sufficient. Missing, unauthenticated or unsupported contexts return empty;
   * blank JWT subjects are treated as absent.
   */
  @Override
  public Optional<String> getCurrentAuditor() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
      return Optional.empty();
    }
    if (auth instanceof DcpAuthenticationToken dcp) {
      return Optional.of(dcp.getPrincipal().participantDid());
    }
    Object principal = auth.getPrincipal();
    if (principal instanceof Jwt jwt) {
      return Optional.ofNullable(jwt.getSubject()).filter(s -> !s.isBlank());
    }
    return Optional.empty();
  }
}
