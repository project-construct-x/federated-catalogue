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

import eu.xfsc.fc.core.dao.oid4vp.ConnectorBindingRepository;
import java.nio.file.AccessDeniedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finalizes the OID4VP bootstrap flow by creating a durable connector binding.
 *
 * <p>The service consumes the challenge referenced by the bootstrap principal and inserts the
 * connector binding in one transaction. A rollback must leave neither a consumed challenge nor a
 * partially created binding behind.
 */
@Service
@RequiredArgsConstructor
public class ConnectorBindingService {
    private final ConnectorBindingRepository bindings;
    private final ConnectorBindingChallengeService challenges;

    /**
     * Binds the connector DID from the path to the verified bootstrap principal.
     *
     * <p>AK 6: on every error the transaction is rolled back and no binding is created.
     *
     * @param p authenticated bootstrap principal from the dedicated filter
     * @param connectorDid connector DID addressed by the request path
     * @return persisted or already existing binding for the connector DID
     * @throws AccessDeniedException if the token was issued for a different connector DID
     */
    @Transactional
    public ConnectorBinding bind(BootstrapPrincipal p, String connectorDid) throws AccessDeniedException {
        if (!connectorDid.equals(p.connectorDid())) {
            throw new AccessDeniedException("connector mismatch");
        }
        challenges.consume(p.challengeId(), connectorDid);
        return bindings.insertIfAbsent(
                connectorDid,
                p.holderDid(),
                p.membershipIssuer(),
                p.presentationId(),
                p.challengeId(),
                p.consumer(),
                p.provider());
    }
}
