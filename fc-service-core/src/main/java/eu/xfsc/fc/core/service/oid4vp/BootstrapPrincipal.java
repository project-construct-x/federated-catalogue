package eu.xfsc.fc.core.service.oid4vp;

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
 * Authenticated principal represented by a catalogue-local bootstrap token.
 *
 * <p>The principal is created after a bootstrap token has been verified and is only intended for
 * OID4VP connector bootstrap or binding endpoints. It must not be treated as a Keycloak or DCP
 * machine principal.
 *
 * @param jti unique JWT identifier of the issued bootstrap token
 * @param holderDid participant DID proven by the membership credential
 * @param connectorDid connector DID for which the token was issued
 * @param challengeId connector binding challenge bound to this bootstrap flow
 * @param membershipIssuer trusted issuer of the verified membership credential
 * @param consumer whether the holder is allowed to act as a consumer connector
 * @param provider whether the holder is allowed to act as a provider connector
 * @param presentationId identifier of the verified presentation or credential fallback
 */
public record BootstrapPrincipal(
    String jti,
    String holderDid,
    String connectorDid,
    String challengeId,
    String membershipIssuer,
    boolean consumer,
    boolean provider,
    String presentationId
) {
}

