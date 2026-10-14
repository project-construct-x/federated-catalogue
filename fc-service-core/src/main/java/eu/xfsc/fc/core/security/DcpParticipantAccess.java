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

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

/** Participant scoping for DCP operations, without any role-based bypass or delegation. */
public final class DcpParticipantAccess {
  private DcpParticipantAccess() {
  }

  /** Rejects missing, unauthenticated and non-DCP authentication contexts. */
  public static DcpIdentity requireIdentity(Authentication authentication) {
    if (!(authentication instanceof DcpAuthenticationToken dcp) || !dcp.isAuthenticated()) {
      throw new AccessDeniedException("Authenticated DCP identity required");
    }
    return dcp.getPrincipal();
  }

  /**
   * Checks an explicitly supplied resource participant DID. The caller determines ownership;
   * this method does not infer it from credential issuers or actor identifiers.
   */
  public static void checkAccess(Authentication authentication, String resourceParticipantDid) {
    DcpIdentity identity = requireIdentity(authentication);
    if (!identity.participantDid().equals(resourceParticipantDid)) {
      throw new AccessDeniedException("Resource does not belong to the authenticated participant");
    }
  }
}
