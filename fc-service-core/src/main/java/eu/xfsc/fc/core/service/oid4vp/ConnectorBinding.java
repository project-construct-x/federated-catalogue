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

import java.time.Instant;

/** Persisted relation between a connector DID and a verified participant holder DID. */
public record ConnectorBinding(
    String id,
    String connectorDid,
    String holderDid,
    String membershipIssuer,
    String presentationId,
    String challengeId,
    Instant boundAt,
    String bindingStatus,
    boolean consumer,
    boolean provider
) {
}

