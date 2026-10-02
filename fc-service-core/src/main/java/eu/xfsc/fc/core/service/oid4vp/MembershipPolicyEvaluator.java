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
import eu.xfsc.fc.api.FcMediaTypes;
import eu.xfsc.fc.core.pojo.ContentAccessorDirect;
import eu.xfsc.fc.core.pojo.CredentialVerificationResult;
import eu.xfsc.fc.core.service.verification.VerificationService;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Evaluates the catalogue membership policy for OID4VP bootstrap presentations.
 *
 * <p>The checks intentionally mirror the DCP asset-write authentication path: a trusted membership
 * issuer, a single membership credential, an unambiguous credential subject, matching holder DID
 * and successful verification by {@link VerificationService} are required before connector roles
 * are accepted.
 */
@Component
public class MembershipPolicyEvaluator {

    private final VerificationService verification;

    public MembershipPolicyEvaluator(
            @Qualifier("verificationServiceImpl") VerificationService verification) {
        this.verification = verification;
    }

    /**
     * Validates a membership presentation and extracts the verified binding-relevant attributes.
     *
     * <p>The method verifies that:
     * <ul>
     *   <li>the presentation and expected holder DID are present,</li>
     *   <li>the presentation holder matches the expected holder DID,</li>
     *   <li>exactly one {@code MembershipCredential} is presented,</li>
     *   <li>the membership issuer is trusted,</li>
     *   <li>the credential subject is exactly the expected holder DID,</li>
     *   <li>{@link VerificationService} confirms the presentation and credential, and</li>
     *   <li>at least one supported connector role is declared.</li>
     * </ul>
     *
     * @param presentation parsed JWT VP or JSON-LD presentation node
     * @param expectedHolderDid holder DID expected from the OID4VP presentation context
     * @param trustedIssuers configured membership issuers trusted by this catalogue
     * @return verified membership attributes used for connector binding
     */
    public VerifiedMembership evaluate(JsonNode presentation, String expectedHolderDid,
                                       Collection<String> trustedIssuers) {
        requirePresentation(presentation);
        requireExpectedHolderDid(expectedHolderDid);
        if (trustedIssuers == null || trustedIssuers.isEmpty()) {
            throw new IllegalArgumentException("At least one trusted membership issuer is required");
        }

        assertPresentationHolder(presentation, expectedHolderDid);

        JsonNode root = PresentationParser.presentationRoot(presentation);
        if (root != null && root.has("vp")) {
            root = root.get("vp");
        }

        JsonNode credentials = root == null ? null : root.get("verifiableCredential");
        if (credentials == null || !credentials.isArray() || credentials.size() != 1) {
            throw new IllegalArgumentException("Exactly one membership credential required");
        }

        JsonNode credential = credentials.get(0);
        String membershipJwt = credential.isTextual()
                ? credential.asText()
                : PresentationParser.extractEnvelopedJwtFromCredential(credential);
        String issuer = extractMembershipIssuer(presentation, membershipJwt);
        if (!trustedIssuers.contains(issuer)) {
            throw new IllegalArgumentException("Signed membership issuer must match trust policy");
        }

        if (!"MembershipCredential".equals(PresentationParser.extractCredentialType(
                presentation, List.of("MembershipCredential")))) {
            throw new IllegalArgumentException("MembershipCredential type required");
        }

        String extractedIssuer = PresentationParser.extractIssuer(presentation);
        if (extractedIssuer != null && !issuer.equals(extractedIssuer)) {
            throw new IllegalArgumentException("Membership issuer mismatch");
        }

        var subjects = PresentationParser.collectCredentialSubjects(presentation);
        if (subjects.size() != 1 || !subjects.getFirst().path("id").isTextual()) {
            throw new IllegalArgumentException("Unambiguous membership subject required");
        }

        JsonNode subject = subjects.getFirst();
        String holderDid = subject.path("id").asText();
        if (!holderDid.startsWith("did:") || !holderDid.equals(expectedHolderDid)) {
            throw new IllegalArgumentException("Membership subject must match authenticated holder");
        }

        CredentialVerificationResult verified = verifyPresentation(presentation);
        if (verified == null || verified.getValidators() == null || verified.getValidators().size() < 2
                || !holderDid.equals(verified.getId()) || !issuer.equals(verified.getIssuer())) {
            throw new IllegalArgumentException("Verified membership and both signatures required");
        }
        if (!"ACTIVE".equalsIgnoreCase(verified.getLifecycleStatus())) {
            throw new IllegalArgumentException("Membership credential status must be ACTIVE");
        }

        boolean consumer = booleanClaim(subject, "isConsumer", "consumer");
        boolean provider = booleanClaim(subject, "isProvider", "provider");
        if (!consumer && !provider) {
            throw new IllegalArgumentException("Membership must declare consumer or provider role");
        }

        return new VerifiedMembership(holderDid, issuer, consumer, provider,
                extractPresentationId(presentation, membershipJwt, holderDid));
    }

    /**
     * Ensures that a presentation node was supplied.
     *
     * @param presentation presentation node to validate
     */
    private void requirePresentation(JsonNode presentation) {
        if (presentation == null || presentation.isNull() || presentation.isMissingNode()) {
            throw new IllegalArgumentException("Membership presentation is required");
        }
    }

    /**
     * Ensures that the expected holder identifier is a DID.
     *
     * @param did expected holder DID
     */
    private void requireExpectedHolderDid(String did) {
        if (did == null || did.isBlank() || !did.startsWith("did:")) {
            throw new IllegalArgumentException("expectedHolderDid must be a DID");
        }
    }

    /**
     * Checks that a signed JWT VP was issued by the expected holder DID.
     *
     * @param presentation presentation node to inspect
     * @param expectedHolderDid holder DID expected as JWT issuer
     */
    private void assertPresentationHolder(JsonNode presentation, String expectedHolderDid) {
        if (!presentation.isTextual()) {
            return;
        }
        try {
            String issuer = SignedJWT.parse(presentation.asText()).getJWTClaimsSet().getIssuer();
            if (!expectedHolderDid.equals(issuer)) {
                throw new IllegalArgumentException("Signed holder presentation required");
            }
        } catch (java.text.ParseException e) {
            throw new IllegalArgumentException("Signed holder presentation required", e);
        }
    }

    /**
     * Extracts the membership credential issuer from a credential JWT or JSON-LD presentation.
     *
     * @param presentation full presentation used for JSON-LD fallback extraction
     * @param membershipJwt embedded membership credential JWT, if present
     * @return issuer DID or URI of the membership credential
     */
    private String extractMembershipIssuer(JsonNode presentation, String membershipJwt) {
        if (membershipJwt != null && !membershipJwt.isBlank()) {
            try {
                return SignedJWT.parse(membershipJwt).getJWTClaimsSet().getIssuer();
            } catch (java.text.ParseException e) {
                throw new IllegalArgumentException("Signed membership credential required", e);
            }
        }
        String issuer = PresentationParser.extractIssuer(presentation);
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("Membership issuer required");
        }
        return issuer;
    }

    /**
     * Delegates cryptographic and semantic verification to the core verification service.
     *
     * @param presentation presentation to verify
     * @return verification result returned by {@link VerificationService}
     */
    private CredentialVerificationResult verifyPresentation(JsonNode presentation) {
        String content = presentation.isTextual() ? presentation.asText() : presentation.toString();
        String mediaType = presentation.isTextual()
                ? FcMediaTypes.VP_JWT_VALUE : FcMediaTypes.VP_LD_JSON_VALUE;
        return verification.verifyCredential(
                new ContentAccessorDirect(content, mediaType), true, true, true, false);
    }

    /**
     * Reads a boolean role claim from the credential subject using a primary and fallback name.
     *
     * @param subject credential subject node
     * @param primaryName preferred claim name
     * @param fallbackName legacy or alternate claim name
     * @return boolean claim value, or {@code false} if no supported value exists
     */
    private boolean booleanClaim(JsonNode subject, String primaryName, String fallbackName) {
        JsonNode value = subject.get(primaryName);
        if (value == null || value.isNull() || value.isMissingNode()) {
            value = subject.get(fallbackName);
        }
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isTextual()) {
            return Boolean.parseBoolean(value.asText());
        }
        return false;
    }

    /**
     * Extracts a traceable presentation identifier with JWT and credential fallback handling.
     *
     * @param presentation verified presentation
     * @param membershipJwt embedded membership credential JWT, if present
     * @param fallback value used when no explicit identifier exists
     * @return presentation identifier used in binding records and bootstrap tokens
     */
    private String extractPresentationId(JsonNode presentation, String membershipJwt, String fallback) {
        if (presentation.hasNonNull("id")) {
            return presentation.get("id").asText();
        }
        if (presentation.isTextual()) {
            try {
                String jwtId = SignedJWT.parse(presentation.asText()).getJWTClaimsSet().getJWTID();
                if (jwtId != null && !jwtId.isBlank()) {
                    return jwtId;
                }
            } catch (java.text.ParseException ignored) {
                // The presentation was already parsed during policy validation; keep fallback here.
            }
        }
        if (membershipJwt != null && !membershipJwt.isBlank()) {
            try {
                String jwtId = SignedJWT.parse(membershipJwt).getJWTClaimsSet().getJWTID();
                if (jwtId != null && !jwtId.isBlank()) {
                    return jwtId;
                }
            } catch (java.text.ParseException ignored) {
                // The credential was already parsed during policy validation; keep fallback here.
            }
        }
        return fallback;
    }
}
