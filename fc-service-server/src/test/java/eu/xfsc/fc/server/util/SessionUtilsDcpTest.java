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

import static org.junit.jupiter.api.Assertions.*;

import eu.xfsc.fc.core.security.DcpAuthenticationToken;
import eu.xfsc.fc.core.security.DcpIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SessionUtilsDcpTest {
  private final DcpIdentity identity = new DcpIdentity("did:web:participant", "did:web:actor");

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void usesMembershipSubjectAndSeparateActorWithoutKeycloakClaims() {
    SecurityContextHolder.getContext().setAuthentication(new DcpAuthenticationToken(identity));
    assertEquals(identity.participantDid(), SessionUtils.getSessionParticipantId());
    assertEquals(identity.actorDid(), SessionUtils.getSessionUserId());
    assertDoesNotThrow(() -> SessionUtils.checkParticipantAccess(identity.participantDid()));
    assertThrows(AccessDeniedException.class, () -> SessionUtils.checkParticipantAccess(identity.actorDid()));
  }

  @Test
  void rejectsMissingContextAndAdminRoleBypass() {
    assertThrows(AccessDeniedException.class, () -> SessionUtils.checkParticipantAccess(identity.participantDid()));
    SecurityContextHolder.getContext().setAuthentication(
        new TestingAuthenticationToken(identity, null, "ROLE_ADMIN_ALL", "ROLE_Ro-MU-CA"));
    assertThrows(AccessDeniedException.class, () -> SessionUtils.checkParticipantAccess(identity.participantDid()));
  }

  @Test
  void rejectsUnauthenticatedDcpAndDcpOnAdminPath() {
    DcpAuthenticationToken token = new DcpAuthenticationToken(identity);
    SecurityContextHolder.getContext().setAuthentication(token);
    assertThrows(AccessDeniedException.class, SessionUtils::requireApplicationAdmin);
    token.setAuthenticated(false);
    assertThrows(AccessDeniedException.class, SessionUtils::getSessionParticipantId);
    assertThrows(AccessDeniedException.class, () -> SessionUtils.checkParticipantAccess(identity.participantDid()));
  }
}
