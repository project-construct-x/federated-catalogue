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
import de.eecc.oid4vc.oid4vp.Constants;
import de.eecc.oid4vc.oid4vp.DcqlQuery;
import de.eecc.oid4vc.oid4vp.PresentationClaims;
import de.eecc.oid4vc.oid4vp.api.Oid4Vp;
import de.eecc.oid4vc.oid4vp.request.PresentationRequestDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates OID4VP presentation requests for connector bootstrap and stores matching challenges.
 *
 * <p>The service builds the membership DCQL request, lets the EECC OID4VP library create the
 * protocol request and then persists a connector-bound challenge using the generated state.
 */
@Service
@RequiredArgsConstructor
public class Oid4vpPresentationRequestService {
    private final Oid4Vp oid4Vp;                       // EECC-Bibliothek
    private final ConnectorBindingChallengeService challenges;
    private final Oid4vpBootstrapProperties props;

    /**
     * Creates a new bootstrap presentation request for a connector DID.
     *
     * @param connectorDid connector DID that requests the bootstrap flow
     * @return presentation request metadata and catalogue challenge information
     */
    public PresentationRequestResult createBootstrapRequest(String connectorDid) {
        var dcql = DcqlQueries.membership(props.getTrustedMembershipIssuers(),
                props.isRequireCredentialStatus());

        PresentationRequestDefinition myDefinition = new PresentationRequestDefinition() {
            /**
             * Supplies the membership DCQL query requested from the wallet.
             *
             * @return DCQL query definition for one membership credential
             */
            @Override
            public DcqlQuery.Query dcqlQuery() {
                return new DcqlQuery.Query(List.of(
                        new DcqlQuery.CredentialQuery(
                                "my_credential_query_id",
                                Constants.VP_FORMAT_JWT_VC_JSON,
                                /*Map.of("type_values", List.of(List.of("VerifiableCredential", "MyCredentialType")))*/
                                dcql,
                                List.of(
                                        new DcqlQuery.ClaimsQuery("subject", List.of("credentialSubject", "id")),
                                        new DcqlQuery.ClaimsQuery("isConsumer",
                                                List.of("credentialSubject", "isConsumer")),
                                        new DcqlQuery.ClaimsQuery("isProvider",
                                                List.of("credentialSubject", "isProvider")),
                                        new DcqlQuery.ClaimsQuery("credentialStatus", List.of("credentialStatus"))
                                ),
                                true)));
            }

            /**
             * Extracts normalized membership claims from a presented credential.
             *
             * @param jsonNode credential JSON passed by the OID4VP library
             * @return normalized presentation claims used by the verifier library
             */
            @Override
            public PresentationClaims extractPresentationClaims(JsonNode jsonNode) {
                Map<String, Object> claimValues = new LinkedHashMap<>();

                putIfPresent(claimValues, "holderDid", firstText(
                        jsonNode.at("/credentialSubject/id"),
                        jsonNode.at("/vc/credentialSubject/id"),
                        jsonNode.at("/sub")
                ));

                putIfPresent(claimValues, "issuer", firstText(
                        jsonNode.at("/issuer/id"),
                        jsonNode.at("/issuer"),
                        jsonNode.at("/vc/issuer/id"),
                        jsonNode.at("/vc/issuer"),
                        jsonNode.at("/iss")
                ));

                putIfPresent(claimValues, "credentialId", firstText(
                        jsonNode.at("/id"),
                        jsonNode.at("/vc/id"),
                        jsonNode.at("/jti")
                ));

                putIfPresent(claimValues, "isConsumer", firstBoolean(
                        jsonNode.at("/credentialSubject/isConsumer"),
                        jsonNode.at("/vc/credentialSubject/isConsumer")
                ));

                putIfPresent(claimValues, "isProvider", firstBoolean(
                        jsonNode.at("/credentialSubject/isProvider"),
                        jsonNode.at("/vc/credentialSubject/isProvider")
                ));

                putIfPresent(claimValues, "credentialStatus", firstText(
                        jsonNode.at("/credentialStatus/id"),
                        jsonNode.at("/credentialStatus"),
                        jsonNode.at("/vc/credentialStatus/id"),
                        jsonNode.at("/vc/credentialStatus")
                ));

                String credentialType = credentialType(jsonNode);

                String identifier = firstText(
                        jsonNode.at("/id"),
                        jsonNode.at("/vc/id"),
                        jsonNode.at("/jti"),
                        jsonNode.at("/credentialSubject/id"),
                        jsonNode.at("/vc/credentialSubject/id"),
                        jsonNode.at("/sub")
                );

                if (identifier == null) {
                    identifier = "membership_credential";
                }

                String name = credentialType != null ? credentialType : "MembershipCredential";

                List<String> values = new ArrayList<>();
                claimValues.values().forEach(value -> values.add(String.valueOf(value)));

                return new ExtractedPresentationClaims(
                        identifier,
                        name,
                        values,
                        credentialType,
                        Map.copyOf(claimValues)
                );
            }
        };

//        PresentationRequest request = oid4Vp.generatePresentationRequest(
//                GenerateRequestOptions.<PresentationRequest>builder(myPresentationDefinition)
//                        .redirect(true)
//                        .builderSupplier(() -> PresentationRequest.builder().purpose("LOGIN"))
//                        .build());
//        return oid4Vp.createPresentationRequest(dcql, props.getVerifierUrl(),
//                Map.of("connector_did", connectorDid, "challenge_id", challenge.id()));
        var request = oid4Vp.generatePresentationRequest(myDefinition);
        var challenge = challenges.issue(connectorDid, request.getState());
        return toResult(request, connectorDid, challenge.id());
        // liefert state + request_uri (bzw. openid4vp://…) für die Wallet
    }

    /**
     * Maps the generated library request and persisted challenge to the API result DTO.
     *
     * @param request OID4VP presentation request created by the verifier library
     * @param connectorDid connector DID for which the request was created
     * @param challengeId persisted connector binding challenge id
     * @return DTO returned to the connector
     */
    private PresentationRequestResult toResult(
            de.eecc.oid4vc.oid4vp.request.PresentationRequest request,
            String connectorDid,
            String challengeId
    ) {
        return new PresentationRequestResult(
                request.getState(),
                request.getRequestUri(),
                request.getClientId(),
                connectorDid,
                challengeId
        );
    }

    /**
     * Adds a claim value to the extraction map if it was present in the credential.
     *
     * @param claims mutable claim map
     * @param key normalized claim key
     * @param value extracted value, or {@code null}
     */
    private static void putIfPresent(Map<String, Object> claims, String key, Object value) {
        if (value != null) {
            claims.put(key, value);
        }
    }

    /**
     * Returns the first scalar value from a list of JSON paths.
     *
     * @param nodes candidate nodes in preference order
     * @return first textual representation, or {@code null}
     */
    private static String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                continue;
            }
            if (node.isTextual() || node.isNumber() || node.isBoolean()) {
                return node.asText();
            }
        }
        return null;
    }

    /**
     * Returns the first boolean value from a list of JSON paths.
     *
     * @param nodes candidate nodes in preference order
     * @return first boolean value, or {@code null}
     */
    private static Boolean firstBoolean(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                continue;
            }
            if (node.isBoolean()) {
                return node.asBoolean();
            }
            if (node.isTextual()) {
                return Boolean.parseBoolean(node.asText());
            }
        }
        return null;
    }

    /**
     * Extracts the credential type from direct JWT-style or nested VC-style JSON structures.
     *
     * @param root credential root node
     * @return credential type if present
     */
    private static String credentialType(JsonNode root) {
        String direct = typeValue(root.at("/type"));
        if (direct != null) {
            return direct;
        }
        return typeValue(root.at("/vc/type"));
    }

    /**
     * Normalizes a JSON-LD type value, preferring {@code MembershipCredential} in arrays.
     *
     * @param typeNode JSON node containing a type string or array
     * @return selected type value, or {@code null}
     */
    private static String typeValue(JsonNode typeNode) {
        if (typeNode == null || typeNode.isMissingNode() || typeNode.isNull()) {
            return null;
        }
        if (typeNode.isTextual()) {
            return typeNode.asText();
        }
        if (typeNode.isArray()) {
            for (JsonNode item : typeNode) {
                if (item != null && item.isTextual() && "MembershipCredential".equals(item.asText())) {
                    return item.asText();
                }
            }
            for (JsonNode item : typeNode) {
                if (item != null && item.isTextual()) {
                    return item.asText();
                }
            }
        }
        return null;
    }

    /**
     * Presentation-claim adapter returned to the EECC OID4VP library.
     *
     * @param identifier stable identifier used by the library for the extracted credential
     * @param name human-readable claim set name
     * @param values flattened extracted claim values
     * @param credentialType extracted credential type
     * @param claimValues normalized extracted claim map
     */
    private record ExtractedPresentationClaims(
            String identifier,
            String name,
            List<String> values,
            String credentialType,
            Map<String, Object> claimValues
    ) implements PresentationClaims {
    }
}
