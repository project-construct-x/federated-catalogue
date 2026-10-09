package eu.xfsc.fc.server.util;

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

import static eu.xfsc.fc.server.util.CommonConstants.ADMIN_ALL_WITH_PREFIX;

import java.util.Collection;
import eu.xfsc.fc.core.security.DcpIdentity;
import eu.xfsc.fc.core.security.DcpParticipantAccess;
import org.springframework.security.core.Authentication;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Utility class with static methods for getting data from the active user session.
 */
@Slf4j
public class SessionUtils {
  /** Returns the verified membership subject DID; Keycloak claims cannot identify a machine caller. */
  public static String getSessionParticipantId() {
    return requireDcpIdentity().participantDid();
  }

  /** Requires the trusted context established by DCP authentication. */
  public static DcpIdentity requireDcpIdentity() {
    return DcpParticipantAccess.requireIdentity(SecurityContextHolder.getContext().getAuthentication());
  }

  /** Administrative account management is independent of catalogue participant ownership. */
  public static void requireApplicationAdmin() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()
        || !(authentication.getPrincipal() instanceof Jwt)
        || authentication.getAuthorities().stream()
            .noneMatch(authority -> ADMIN_ALL_WITH_PREFIX.equals(authority.getAuthority()))) {
      throw new AccessDeniedException("Authenticated application administrator required");
    }
  }

  /**
   * Public static method to get User ID from the active user session.
   *
   * @return String user Id.
   */
  public static String getSessionUserId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return null;
    }
    if (authentication.getPrincipal() instanceof DcpIdentity) {
      return DcpParticipantAccess.requireIdentity(authentication).actorDid();
    }
    String userId = null;
    Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    if (principal instanceof Jwt) {
      userId = ((Jwt) principal).getSubject();
    }
    return userId;
  }

  /**
   * Public static method to check if a user has a role.
   *
   * @param role role to be checked.
   * @return boolean status.
   */
  public static boolean sessionUserHasRole(String role) {
    Collection<GrantedAuthority> authorities = (Collection<GrantedAuthority>)
        SecurityContextHolder.getContext().getAuthentication().getAuthorities();
    return authorities.stream().anyMatch(authority -> authority.getAuthority().equals(role));
  }


  /**
   * Public static method to session user all roles .
   *
   * @return List<String> roles.
   */
  public static List<String> getSessionUserRoles() {
    List<String> authorities = SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                                .stream()
                                .map(authority -> authority.getAuthority()).collect( Collectors.toList());
    return authorities;
  }
  /** Checks resource ownership using DCP only; administrator roles do not bypass this check. */
  public static void checkParticipantAccess(String participantId) {
    DcpParticipantAccess.checkAccess(SecurityContextHolder.getContext().getAuthentication(), participantId);
  }

  /**
   * Clear spring security context.
   *
   */
  public static void logoutSessionUser() {
    SecurityContextHolder.getContext().setAuthentication(null);
    SecurityContextHolder.clearContext();
    log.debug("logoutSessionUser.exit;");
  }
}
