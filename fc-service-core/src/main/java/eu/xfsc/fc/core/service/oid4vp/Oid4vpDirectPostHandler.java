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

/**
 * Handles OID4VP direct-post responses from wallets during connector bootstrap.
 *
 * <p>The EECC OID4VP library validates protocol-level nonce, state and replay properties. This
 * handler adds catalogue-specific membership policy validation and connector-binding authorization
 * before allowing the library to issue a response code.
 */
@Service
@RequiredArgsConstructor
public class Oid4vpDirectPostHandler {
    private final Oid4Vp oid4Vp;
    private final ConnectorBindingChallengeService challenges;
    private final MembershipPolicyEvaluator policy;
    private final ConnectorBindingAuthorizer authorizer;
    private final Oid4vpBootstrapProperties props;

    /**
     * Processes a wallet direct-post response and returns an OID4VP response code.
     *
     * <p>Nonce, state and replay handling are delegated to the OID4VP library; this method only
     * performs catalogue policy checks on the parsed presentation.
     *
     * @param vpToken submitted {@code vp_token} form field
     * @param state submitted OID4VP state form field
     * @return direct-post response containing the response code
     */
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

    /**
     * Selects the presentation object from library-parsed {@code vp_token} JSON shapes.
     *
     * @param vpToken parsed VP token JSON
     * @return first presentation node accepted by the membership policy evaluator
     */
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

    /**
     * Returns the first item of a parsed VP token array.
     *
     * @param array VP token array
     * @return first array element
     */
    private JsonNode firstArrayElement(JsonNode array) {
        if (array.isEmpty()) {
            throw new IllegalArgumentException("vp_token must contain a presentation");
        }
        return array.get(0);
    }

    /**
     * Extracts the expected membership holder DID from a JWT or JSON-LD presentation.
     *
     * @param presentation selected presentation node
     * @return holder DID used as expected subject during policy validation
     */
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

    /**
     * Returns the first textual JSON node from a list of candidate paths.
     *
     * @param nodes candidate nodes in preference order
     * @return first text value, or {@code null} if none exists
     */
    private static String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull() && node.isTextual()) {
                return node.asText();
            }
        }
        return null;
    }
}
