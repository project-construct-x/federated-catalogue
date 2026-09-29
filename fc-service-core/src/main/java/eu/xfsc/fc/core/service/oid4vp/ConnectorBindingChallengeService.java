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

import eu.xfsc.fc.core.dao.oid4vp.ConnectorBindingChallengeRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConnectorBindingChallengeService {

    private final ConnectorBindingChallengeRepository repository;
    private final Oid4vpBootstrapProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public Challenge issue(String connectorDid) {
        return issue(connectorDid, UUID.randomUUID().toString());
    }

    @Transactional
    public Challenge issue(String connectorDid, String state) {
        if (connectorDid == null || connectorDid.isBlank()) {
            throw new IllegalArgumentException("connectorDid must not be blank");
        }
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("state must not be blank");
        }

        Instant now = Instant.now();
        Challenge challenge = new Challenge(
                UUID.randomUUID().toString(),
                connectorDid,
                generateNonce(),
                state,
                now,
                now.plus(properties.getChallengeTtl()));
        repository.save(challenge);
        return challenge;
    }

    @Transactional(readOnly = true)
    public Optional<Challenge> findByState(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        return repository.findByState(state);
    }

    @Transactional(readOnly = true)
    public Optional<Challenge> findActiveByState(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        return repository.findActiveByState(state, Instant.now());
    }

    @Transactional(readOnly = true)
    public Optional<Challenge> findActive(String challengeId, String connectorDid) {
        if (challengeId == null || challengeId.isBlank()
                || connectorDid == null || connectorDid.isBlank()) {
            return Optional.empty();
        }
        return repository.findActiveByChallengeIdAndConnectorDid(
                challengeId, connectorDid, Instant.now());
    }

    @Transactional
    public boolean consume(String challengeId, String connectorDid) {
        if (challengeId == null || challengeId.isBlank()) {
            throw new IllegalArgumentException("challengeId must not be blank");
        }
        if (connectorDid == null || connectorDid.isBlank()) {
            throw new IllegalArgumentException("connectorDid must not be blank");
        }

        boolean consumed = repository.consumeIfActive(challengeId, connectorDid, Instant.now());
        if (!consumed) {
            throw new IllegalArgumentException(
                    "Connector binding challenge is expired, revoked, unknown or already consumed");
        }
        return true;
    }

    private String generateNonce() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
