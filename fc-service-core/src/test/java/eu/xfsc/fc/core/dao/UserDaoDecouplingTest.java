package eu.xfsc.fc.core.dao;

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
import static org.mockito.Mockito.*;
import eu.xfsc.fc.api.generated.model.User;
import eu.xfsc.fc.core.dao.impl.UserDaoImpl;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.test.util.ReflectionTestUtils;

class UserDaoDecouplingTest {
  @Test void adminRepresentationNeverSetsParticipantAttributesOrGroups() {
    User admin = new User().email("admin@example.org").firstName("Admin").lastName("User")
        .participantId("did:web:legacy");
    var representation = UserDaoImpl.toUserRepo(admin);
    assertNull(representation.getGroups());
    assertNull(representation.getAttributes());
    admin.setParticipantId(null);
    assertDoesNotThrow(() -> UserDaoImpl.toUserRepo(admin));
  }

  @Test void oldKeycloakAttributesCannotSupplyCatalogueParticipantIdentity() {
    UserRepresentation old = new UserRepresentation();
    old.setAttributes(Map.of("participantId", List.of("did:web:legacy")));
    old.setGroups(List.of("/legacy-participant"));
    assertNull(UserDaoImpl.toUserProfile(old, List.of()).getParticipantId());
  }

  @Test void updatingAdminDoesNotReadOrChangeAnyGroup() {
    Keycloak keycloak = mock(Keycloak.class, RETURNS_DEEP_STUBS);
    UserDaoImpl dao = new UserDaoImpl();
    ReflectionTestUtils.setField(dao, "keycloak", keycloak);
    ReflectionTestUtils.setField(dao, "realm", "test");
    ReflectionTestUtils.setField(dao, "resourceId", "catalogue");
    var realm = keycloak.realm("test");
    ClientRepresentation client = new ClientRepresentation();
    client.setId("client");
    when(realm.clients().findByClientId("catalogue")).thenReturn(List.of(client));
    when(realm.clients().get("client").roles().list()).thenReturn(List.of());
    var user = realm.users().get("admin");
    UserRepresentation existing = new UserRepresentation();
    existing.setId("admin");
    when(user.toRepresentation()).thenReturn(existing);
    when(user.roles().clientLevel("client").listAll()).thenReturn(List.of());
    dao.update("admin", new User().email("admin@example.org").firstName("A").lastName("B").roleIds(List.of()));
    verify(realm, never()).groups();
    verify(user, never()).groups();
    verify(user, never()).joinGroup(any());
    verify(user, never()).leaveGroup(any());
    verify(user).update(argThat(value -> value.getGroups() == null && value.getAttributes() == null));
  }
}
