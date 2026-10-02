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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.xfsc.fc.core.pojo.CredentialVerificationResult;
import eu.xfsc.fc.core.pojo.Validator;
import eu.xfsc.fc.core.service.verification.VerificationService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MembershipPolicyEvaluatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HOLDER = "did:web:wallet.edc1.construct-x.prod-k8s.eecc.de:edc1";
    private static final String ISSUER = "did:web:issuer.int.construct-x.net:issuer";

    @Mock
    private VerificationService verification;

    private MembershipPolicyEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new MembershipPolicyEvaluator(verification);
    }

    @Test
    void acceptsDcpCompatibleMembershipPresentationShape() throws Exception {
        JsonNode presentation = presentation(ISSUER, HOLDER, true, false, "MembershipCredential");
        when(verification.verifyCredential(any(), eq(true), eq(true), eq(true), eq(false)))
            .thenReturn(verified(HOLDER, ISSUER));

        VerifiedMembership membership = evaluator.evaluate(presentation, HOLDER, List.of(ISSUER));

        assertEquals(HOLDER, membership.holderDid());
        assertEquals(ISSUER, membership.membershipIssuer());
        assertEquals("vp-1", membership.presentationId());
        assertTrue(membership.provider());
    }

    @Test
    void rejectsUntrustedIssuerBeforeVerification() throws Exception {
        JsonNode presentation = presentation("did:web:untrusted.example", HOLDER, true, false,
            "MembershipCredential");

        assertThrows(IllegalArgumentException.class,
            () -> evaluator.evaluate(presentation, HOLDER, List.of(ISSUER)));
        verifyNoInteractions(verification);
    }

    @Test
    void rejectsSubjectMismatchBeforeVerification() throws Exception {
        JsonNode presentation = presentation(ISSUER, "did:web:other.example", true, false,
            "MembershipCredential");

        assertThrows(IllegalArgumentException.class,
            () -> evaluator.evaluate(presentation, HOLDER, List.of(ISSUER)));
        verifyNoInteractions(verification);
    }

    @Test
    void rejectsMissingConnectorRole() throws Exception {
        JsonNode presentation = presentation(ISSUER, HOLDER, false, false, "MembershipCredential");
        when(verification.verifyCredential(any(), eq(true), eq(true), eq(true), eq(false)))
            .thenReturn(verified(HOLDER, ISSUER));

        assertThrows(IllegalArgumentException.class,
            () -> evaluator.evaluate(presentation, HOLDER, List.of(ISSUER)));
    }

    @Test
    void rejectsVerificationResultThatDoesNotMatchMembership() throws Exception {
        JsonNode presentation = presentation(ISSUER, HOLDER, true, false, "MembershipCredential");
        when(verification.verifyCredential(any(), eq(true), eq(true), eq(true), eq(false)))
            .thenReturn(verified(HOLDER, "did:web:other-issuer.example"));

        assertThrows(IllegalArgumentException.class,
            () -> evaluator.evaluate(presentation, HOLDER, List.of(ISSUER)));
    }

    private JsonNode presentation(String issuer, String subject, boolean provider, boolean consumer,
                                  String credentialType) throws Exception {
        return MAPPER.readTree("""
            {
              "id": "vp-1",
              "type": ["VerifiablePresentation"],
              "holder": "%s",
              "verifiableCredential": [{
                "@context": ["https://www.w3.org/2018/credentials/v1"],
                "id": "ad644042-2654-4d3f-8d9b-b755bc8bdc9f",
                "type": ["VerifiableCredential", "%s"],
                "issuer": "%s",
                "issuanceDate": "2026-07-14T11:50:43.572911461Z",
                "expirationDate": "2027-01-10T11:50:43.572911791Z",
                "credentialSubject": {
                  "id": "%s",
                  "isConsumer": %s,
                  "isProvider": %s
                }
              }]
            }
            """.formatted(HOLDER, credentialType, issuer, subject, consumer, provider));
    }

    private CredentialVerificationResult verified(String holder, String issuer) {
        return new CredentialVerificationResult(Instant.now(), "ACTIVE", issuer, Instant.now(), holder,
            List.of(), List.of(new Validator(holder + "#key", null, null),
                new Validator(issuer + "#key", null, null)), null, null);
    }
}
