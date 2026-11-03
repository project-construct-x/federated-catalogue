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

/**
 * Persisted relation between a connector DID and a verified participant holder DID.
 *
 * <p>A binding is created after the OID4VP presentation, membership policy and connector ownership
 * checks succeeded. It is the durable authorization basis for later connector bootstrap actions.
 *
 * @param id database primary key
 * @param connectorDid connector DID bound to the membership holder
 * @param holderDid participant DID proven by the membership credential
 * @param membershipIssuer trusted issuer of the membership credential
 * @param presentationId identifier of the presentation used for the binding
 * @param challengeId consumed challenge that authorized this binding
 * @param boundAt timestamp at which the binding became active
 * @param bindingStatus lifecycle status of the binding, for example {@code ACTIVE}
 * @param consumer whether the holder has a consumer role
 * @param provider whether the holder has a provider role
 */
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

