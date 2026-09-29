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

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jwt.SignedJWT;
import de.eecc.dcp.vp.PresentationParser;
import de.eecc.oid4vc.oid4vp.VpTokenResponse;
import de.eecc.oid4vc.oid4vp.api.DirectPostResult;
import de.eecc.oid4vc.oid4vp.api.Oid4Vp;
import java.text.ParseException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class Oid4vpDirectPostHandler {
    private final Oid4Vp oid4Vp;
    private final ConnectorBindingChallengeService challenges;
    private final MembershipPolicyEvaluator policy;
    private final ConnectorBindingAuthorizer authorizer;
    private final Oid4vpBootstrapProperties props;

    /** Nicht neu implementieren: Nonce, State und Replay prüft die Bibliothek. */
    public VpTokenResponse.DirectPostResponse handle(String vpToken, String state) {
        return oid4Vp.processDirectPost(vpToken, state, (request, parsedVpToken) -> {
            Challenge challenge = challenges.findActiveByState(request.getState())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No connector binding challenge found for OID4VP state"));
            JsonNode presentation = firstPresentation(parsedVpToken);
            VerifiedMembership m = policy.evaluate(presentation, holderDid(presentation),
                    props.getTrustedMembershipIssuers());
            authorizer.assertMayBind(m, challenge.connectorDid(), challenge.id());
            return DirectPostResult.issueResponseCode();
        });
    }

    private JsonNode firstPresentation(JsonNode vpToken) {
        if (vpToken == null || vpToken.isNull() || vpToken.isMissingNode()) {
            throw new IllegalArgumentException("vp_token must contain a presentation");
        }
        if (vpToken.isTextual() || vpToken.has("vp") || vpToken.has("verifiableCredential")) {
            return vpToken;
        }
        if (vpToken.isArray()) {
            return firstArrayElement(vpToken);
        }
        if (vpToken.isObject()) {
            for (Map.Entry<String, JsonNode> entry : vpToken.properties()) {
                JsonNode value = entry.getValue();
                if (value != null && value.isArray() && !value.isEmpty()) {
                    return value.get(0);
                }
            }
        }
        throw new IllegalArgumentException("vp_token must contain a presentation");
    }

    private JsonNode firstArrayElement(JsonNode array) {
        if (array.isEmpty()) {
            throw new IllegalArgumentException("vp_token must contain a presentation");
        }
        return array.get(0);
    }

    private String holderDid(JsonNode presentation) {
        if (presentation.isTextual()) {
            try {
                return SignedJWT.parse(presentation.asText()).getJWTClaimsSet().getIssuer();
            } catch (ParseException e) {
                throw new IllegalArgumentException("Signed holder presentation required", e);
            }
        }

        String holderDid = firstText(
                presentation.at("/holder/id"),
                presentation.at("/holder"),
                presentation.at("/vp/holder/id"),
                presentation.at("/vp/holder"));
        if (holderDid != null) {
            return holderDid;
        }
        return PresentationParser.extractSubjectId(presentation);
    }

    private static String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull() && node.isTextual()) {
                return node.asText();
            }
        }
        return null;
    }
}
