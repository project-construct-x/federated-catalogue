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

/**
 * Immutable identity data for a machine operation authenticated through DCP.
 *
 * <p>The authentication boundary must construct this only after successful DCP verification,
 * including membership, trusted issuer and holder-binding checks. Construction checks required
 * values only; it does not verify credentials, DID syntax or delegation. This object alone does
 * not establish an authenticated Spring Security context or grant access to a resource.
 *
 * <p>Contains identifiers only, never tokens or complete credentials/presentations.
 *
 * @param participantDid subject DID of the verified MembershipCredential; not the credential issuer
 * @param actorDid separately authenticated actor/holder DID, or {@code null} when not separately
 *     available; no actor identity is inferred from the participant DID
 */
public record DcpIdentity(String participantDid, String actorDid) {

  /**
   * Requires a participant identity and rejects blank actor identities when supplied.
   * Identifiers are preserved exactly as provided by the verifier.
   */
  public DcpIdentity {
    if (participantDid == null || participantDid.isBlank()) {
      throw new IllegalArgumentException("Participant DID must not be null or blank");
    }
    if (actorDid != null && actorDid.isBlank()) {
      throw new IllegalArgumentException("Actor DID must not be blank when supplied");
    }
  }

  /** Returns the fixed authentication method for this identity. */
  public AuthenticationMethod authenticationMethod() {
    return AuthenticationMethod.DCP;
  }

  /** Authentication method represented by this identity model. */
  public enum AuthenticationMethod {
    DCP
  }
}
