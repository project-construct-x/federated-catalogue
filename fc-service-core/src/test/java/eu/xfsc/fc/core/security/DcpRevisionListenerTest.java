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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.xfsc.fc.core.dao.audit.AuthenticationRevision;
import eu.xfsc.fc.core.dao.audit.DcpRevisionListener;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class DcpRevisionListenerTest {
  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private AuthenticationRevision capture() {
    AuthenticationRevision revision = new AuthenticationRevision();
    new DcpRevisionListener().newRevision(revision);
    return revision;
  }

  private void assertNoDcpAttribution() {
    AuthenticationRevision revision = capture();
    assertNull(revision.getParticipantDid());
    assertNull(revision.getActorDid());
    assertNull(revision.getAuthenticationMethod());
  }

  @Test
  void verifiedParticipantWithoutSeparateActorDoesNotInventOne() {
    SecurityContextHolder.getContext().setAuthentication(new DcpAuthenticationToken(
        new DcpIdentity("did:web:member.example", null)));
    AuthenticationRevision revision = capture();
    assertEquals("did:web:member.example", revision.getParticipantDid());
    assertEquals("DCP", revision.getAuthenticationMethod());
    assertNull(revision.getActorDid());
  }

  @Test
  void missingAndUnauthenticatedContextsAreNotAttributed() {
    assertNoDcpAttribution();
    DcpAuthenticationToken token = new DcpAuthenticationToken(new DcpIdentity("did:web:untrusted", null));
    token.setAuthenticated(false);
    SecurityContextHolder.getContext().setAuthentication(token);
    assertNoDcpAttribution();
  }

  @Test
  void identityInAnotherTokenDoesNotEstablishVerifiedDcpIdentity() {
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(new DcpIdentity("did:web:untrusted", null),
            "secret-presentation", List.of()));
    assertNoDcpAttribution();
  }

  @Test
  void jwtParticipantClaimsAndPresentationContentsAreNotCopied() {
    Jwt jwt = Jwt.withTokenValue("secret-token").header("alg", "RS256").subject("admin")
        .claim("participant_id", "did:web:untrusted")
        .claim("vp", "secret-presentation").build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    assertNoDcpAttribution();
  }
}
