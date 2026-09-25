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

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class SessionUtilsTest {
  @AfterEach
  void clearContext() { SecurityContextHolder.clearContext(); }

  @Test
  void keycloakClaimsAndAdminRolesCannotProvideMachineIdentityOrOwnership() {
    Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("admin")
        .claim("participant_id", "did:web:participant").build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
        List.of(new SimpleGrantedAuthority("ROLE_ADMIN_ALL"), new SimpleGrantedAuthority("ROLE_Ro-MU-CA"))));
    assertThrows(AccessDeniedException.class, SessionUtils::getSessionParticipantId);
    assertThrows(AccessDeniedException.class, () -> SessionUtils.checkParticipantAccess("did:web:participant"));
    assertThrows(AccessDeniedException.class, () -> SessionUtils.checkParticipantAccess("did:web:other"));
  }
}
