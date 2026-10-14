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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;

class DcpParticipantAccessTest {
  private final DcpIdentity identity = new DcpIdentity("did:web:participant", "did:web:actor");

  @Test
  void ownParticipantAllowedButActorAndOtherParticipantsDenied() {
    var authentication = new DcpAuthenticationToken(identity);
    assertDoesNotThrow(() -> DcpParticipantAccess.checkAccess(authentication, identity.participantDid()));
    assertThrows(AccessDeniedException.class,
        () -> DcpParticipantAccess.checkAccess(authentication, identity.actorDid()));
    assertThrows(AccessDeniedException.class,
        () -> DcpParticipantAccess.checkAccess(authentication, "did:web:other"));
    assertThrows(AccessDeniedException.class, () -> DcpParticipantAccess.checkAccess(authentication, null));
  }

  @Test
  void missingOrRevokedAuthenticationDenied() {
    assertThrows(AccessDeniedException.class, () -> DcpParticipantAccess.checkAccess(null, identity.participantDid()));
    var authentication = new DcpAuthenticationToken(identity);
    authentication.setAuthenticated(false);
    assertThrows(AccessDeniedException.class,
        () -> DcpParticipantAccess.checkAccess(authentication, identity.participantDid()));
    assertThrows(IllegalArgumentException.class, () -> authentication.setAuthenticated(true));
  }

  @Test
  void adminRolesCannotMakeAnUnverifiedIdentityTrusted() {
    var authentication = new TestingAuthenticationToken(identity, null, "ROLE_ADMIN_ALL", "ROLE_Ro-MU-CA");
    assertThrows(AccessDeniedException.class,
        () -> DcpParticipantAccess.checkAccess(authentication, identity.participantDid()));
  }

  @Test
  void authenticationRetainsNeitherCredentialsNorRoles() {
    var authentication = new DcpAuthenticationToken(identity);
    assertNull(authentication.getCredentials());
    assertTrue(authentication.getAuthorities().isEmpty());
    assertEquals(identity.participantDid(), authentication.getName());
  }
}
