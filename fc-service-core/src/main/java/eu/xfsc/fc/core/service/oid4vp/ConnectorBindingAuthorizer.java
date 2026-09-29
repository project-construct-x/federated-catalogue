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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.xfsc.fc.core.dao.oid4vp.ConnectorBindingRepository;
import eu.xfsc.fc.core.service.resolve.DidDocumentResolver;
import foundation.identity.did.DIDDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** AK 3: Eine Membership allein reicht nicht für die Bindung eines beliebigen Connectors. */
@Service
@RequiredArgsConstructor
public class ConnectorBindingAuthorizer {
    private final ConnectorBindingChallengeService challenges;
    private final ConnectorBindingRepository bindings;
    private final DidDocumentResolver didDocumentResolver;
    private final ObjectMapper objectMapper;

    public void assertMayBind(VerifiedMembership m, String connectorDid, String challengeId) {
        requireMembership(m);
        requireNotBlank(connectorDid, "connectorDid");
        requireNotBlank(challengeId, "challengeId");

        challenges.findActive(challengeId, connectorDid)
                .orElseThrow(() -> denied(
                        "Connector binding challenge is unknown, expired, revoked or already consumed"));

        if (!connectorBelongsToHolder(connectorDid, m.holderDid())) {
            throw denied("Connector DID is not controlled by membership holder");
        }

        bindings.findActiveByConnectorDid(connectorDid)
                .filter(binding -> !binding.holderDid().equals(m.holderDid()))
                .ifPresent(binding -> {
                    throw denied("Connector is already bound to holder " + binding.holderDid());
                });

        if (!m.consumer() && !m.provider()) {
            throw denied("Membership does not contain a supported connector role");
        }
    }

    private void requireMembership(VerifiedMembership membership) {
        if (membership == null) {
            throw denied("Verified membership is required");
        }
        requireNotBlank(membership.holderDid(), "holderDid");
        requireNotBlank(membership.membershipIssuer(), "membershipIssuer");
        requireNotBlank(membership.presentationId(), "presentationId");
    }

    private void requireNotBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw denied(name + " must not be blank");
        }
    }

    private boolean connectorBelongsToHolder(String connectorDid, String holderDid) {
        if (connectorDid.equals(holderDid)) {
            return true;
        }

        DIDDocument document;
        try {
            document = didDocumentResolver.resolveDidDocument(connectorDid);
        } catch (RuntimeException e) {
            throw denied("Connector DID document could not be resolved", e);
        }

        JsonNode root = parseDidDocument(document);
        return fieldContainsDid(root.get("controller"), holderDid)
                || fieldContainsDid(root.get("alsoKnownAs"), holderDid);
    }

    private JsonNode parseDidDocument(DIDDocument document) {
        try {
            return objectMapper.readTree(document.toJson());
        } catch (JsonProcessingException e) {
            throw denied("Connector DID document could not be parsed", e);
        }
    }

    private boolean fieldContainsDid(JsonNode node, String did) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return did.equals(node.asText());
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (fieldContainsDid(item, did)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isObject()) {
            return fieldContainsDid(node.get("id"), did);
        }
        return false;
    }

    private AccessDeniedException denied(String message) {
        return new AccessDeniedException(message);
    }

    private AccessDeniedException denied(String message, Throwable cause) {
        return new AccessDeniedException(message, cause);
    }
}
