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
 * Verified membership information extracted from an OID4VP presentation.
 *
 * <p>Instances are only created after the membership credential and presentation have passed the
 * local trust policy and signature or semantic verification checks.
 *
 * @param holderDid participant DID from the verified membership credential subject
 * @param membershipIssuer trusted issuer of the membership credential
 * @param consumer whether the membership allows consumer connector behavior
 * @param provider whether the membership allows provider connector behavior
 * @param presentationId identifier used to trace the verified presentation
 */
public record VerifiedMembership(
    String holderDid,
    String membershipIssuer,
    boolean consumer,
    boolean provider,
    String presentationId
) {
}

