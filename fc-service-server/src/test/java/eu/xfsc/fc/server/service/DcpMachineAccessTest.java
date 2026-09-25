package eu.xfsc.fc.server.service;

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
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

import java.util.List;
import eu.xfsc.fc.core.dao.ParticipantDao;
import eu.xfsc.fc.core.dao.UserDao;
import eu.xfsc.fc.core.pojo.AssetMetadata;
import eu.xfsc.fc.core.pojo.CredentialVerificationResult;
import eu.xfsc.fc.core.pojo.PaginatedResults;
import eu.xfsc.fc.core.security.DcpAuthenticationToken;
import eu.xfsc.fc.core.security.DcpIdentity;
import eu.xfsc.fc.core.service.assetstore.AssetStore;
import eu.xfsc.fc.core.service.verification.VerificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class DcpMachineAccessTest {
  private static final String DID = "did:web:participant.example";
  @Mock AssetStore store;
  @Mock ParticipantDao participantDao;
  @Mock UserDao userDao;
  @Mock VerificationService verification;
  @InjectMocks AssetService assets;
  @InjectMocks AssetUploadService uploads;
  @InjectMocks ParticipantsService participants;
  @InjectMocks UsersService users;

  @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

  private void dcp() {
    SecurityContextHolder.getContext().setAuthentication(new DcpAuthenticationToken(new DcpIdentity(DID, null)));
  }

  private void jwt(String role) {
    Jwt token = Jwt.withTokenValue("token").header("alg", "RS256").subject("admin")
        .claim("participant_id", DID).build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token,
        List.of(new SimpleGrantedAuthority(role))));
  }

  @Test void keycloakAdminCannotReadWriteOrDeleteMachineData() {
    jwt("ROLE_ADMIN_ALL");
    assertThrows(AccessDeniedException.class, () -> assets.readAssetById("asset", null));
    assertThrows(AccessDeniedException.class, () -> assets.deleteAsset("hash"));
    assertThrows(AccessDeniedException.class, () -> assets.deleteAssetById("missing"));
    assertThrows(AccessDeniedException.class, () -> assets.updateAsset("asset", "body", null));
    assertThrows(AccessDeniedException.class, () -> uploads.processUpload(new byte[]{1}, "application/octet-stream", null));
    assertThrows(AccessDeniedException.class, () -> participants.addParticipant("body"));
    assertThrows(AccessDeniedException.class, () -> participants.getParticipants(0, 10));
    assertThrows(AccessDeniedException.class, () -> participants.deleteParticipant(DID));
    verifyNoInteractions(store, participantDao, verification);
  }

  @Test void missingDcpCannotPerformIdempotentDeleteOrBinaryUpload() {
    assertThrows(AccessDeniedException.class, () -> assets.deleteAssetById("missing"));
    assertThrows(AccessDeniedException.class, () -> uploads.processUpload(new byte[]{1}, "application/octet-stream", null));
    verifyNoInteractions(store, verification);
  }

  @Test void dcpCannotReplaceAnotherParticipantsBinaryAsset() {
    dcp();
    AssetMetadata other = new AssetMetadata();
    other.setIssuer("did:web:other.example");
    when(store.getById("existing")).thenReturn(other);
    assertThrows(AccessDeniedException.class,
        () -> uploads.processUpload(new byte[]{1}, "application/octet-stream", null, "existing"));
    verify(store, never()).storeUnverified(any(), any());
    verifyNoInteractions(verification);
  }

  @Test void dcpCanDeleteOwnAssetButNotAnotherParticipantsAsset() {
    dcp();
    AssetMetadata own = new AssetMetadata(); own.setIssuer(DID);
    AssetMetadata other = new AssetMetadata(); other.setIssuer("did:web:other.example");
    when(store.getByHash("own")).thenReturn(own);
    when(store.getByHash("other")).thenReturn(other);
    assertEquals(200, assets.deleteAsset("own").getStatusCode().value());
    verify(store).deleteAsset("own");
    assertThrows(AccessDeniedException.class, () -> assets.deleteAsset("other"));
    verify(store, never()).deleteAsset("other");
  }

  @Test void participantRegistrationMustMatchMembershipSubject() {
    dcp();
    CredentialVerificationResult result = mock(CredentialVerificationResult.class);
    when(result.getId()).thenReturn("did:web:other.example");
    when(verification.verifyCredential(any())).thenReturn(result);
    assertThrows(AccessDeniedException.class, () -> participants.addParticipant("credential"));
    verify(store, never()).storeCredential(any(), any());
    verifyNoInteractions(participantDao);
  }

  @Test void dcpCanRegisterItsOwnParticipant() {
    dcp();
    CredentialVerificationResult result = mock(CredentialVerificationResult.class);
    when(result.getId()).thenReturn(DID);
    when(verification.verifyCredential(any())).thenReturn(result);
    when(participantDao.create(any())).thenAnswer(call -> call.getArgument(0));
    assertEquals(201, participants.addParticipant("credential").getStatusCode().value());
    verify(store).storeCredential(any(), any());
    verify(participantDao).create(any());
  }

  @Test void adminUserListingDoesNotNeedParticipantClaimsOrDcp() {
    Jwt token = Jwt.withTokenValue("token").header("alg", "RS256").subject("admin").build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token,
        List.of(new SimpleGrantedAuthority("ROLE_ADMIN_ALL"))));
    when(userDao.search(null, 0, 10)).thenReturn(new PaginatedResults<>(0, List.of()));
    assertEquals(200, users.getUsers(0, 10).getStatusCode().value());
    verifyNoInteractions(participantDao);
  }

  @Test void dcpAndOldRolesCannotAdministerUsers() {
    dcp();
    assertThrows(AccessDeniedException.class, () -> users.getUsers(0, 10));
    assertThrows(AccessDeniedException.class, () -> participants.getParticipantUsers(DID, 0, 10));
    jwt("ROLE_Ro-MU-CA");
    assertThrows(AccessDeniedException.class, () -> users.getUsers(0, 10));
    verifyNoInteractions(userDao, participantDao);
  }
}
