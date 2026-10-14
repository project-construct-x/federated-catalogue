package eu.xfsc.fc.core.service.dcp;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.eecc.dcp.message.PresentationResponseMessage;
import eu.xfsc.fc.core.config.DcpProperties;
import eu.xfsc.fc.core.exception.VerificationException;
import eu.xfsc.fc.core.service.verification.VerificationService;
import java.util.List;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import eu.xfsc.fc.core.pojo.CredentialVerificationResult;
import eu.xfsc.fc.core.pojo.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;

class DcpMachineAuthenticationServiceTest {
  private static final String HOLDER = "did:web:holder.example";
  private static final String ISSUER = "did:web:issuer.example";
  private final DcpVerifierService verifier = mock(DcpVerifierService.class);
  private final VerificationService verification = mock(VerificationService.class);
  private final DcpProperties properties = new DcpProperties();
  private final DcpMachineAuthenticationService service =
      new DcpMachineAuthenticationService(verifier, verification, properties);

  @BeforeEach
  void configure() {
    properties.setAudience("https://catalogue.example");
    properties.getPostAssets().setRequiredIssuer(ISSUER);
  }

  @Test
  void establishesIdentityOnlyAfterStrictVerification() throws Exception {
    String jwtValidity = ",\"exp\":" + Instant.now().plusSeconds(300).getEpochSecond()
        + ",\"nbf\":" + Instant.now().minusSeconds(300).getEpochSecond();
    response(ISSUER, HOLDER, "MembershipCredential", jwtValidity,
        ",\"validFrom\":\"2000-01-01T00:00:00Z\",\"validUntil\":\"" + Instant.now().plusSeconds(300) + "\"",
        jwtValidity);
    when(verification.verifyCredential(any(), eq(true), eq(true), eq(true), eq(false)))
        .thenReturn(new CredentialVerificationResult(Instant.now(), "ACTIVE", ISSUER, Instant.now(), HOLDER,
            List.of(), List.of(new Validator(HOLDER + "#key", null, null),
                new Validator(ISSUER + "#key", null, null)), null, null));
    var identity = service.authenticateMachineAccess("Bearer si");
    assertEquals(HOLDER, identity.participantDid());
    assertEquals(HOLDER, identity.actorDid());
    verify(verification).verifyCredential(any(), eq(true), eq(true), eq(true), eq(false));
  }

  @Test
  void rejectsCryptographicVerificationFailureWithoutExposingPayload() throws Exception {
    response(ISSUER, HOLDER, "MembershipCredential");
    when(verification.verifyCredential(any(), eq(true), eq(true), eq(true), eq(false)))
        .thenThrow(new VerificationException("sensitive credential body"));
    var error = assertThrows(BadCredentialsException.class, () -> service.authenticateMachineAccess("Bearer si"));
    assertEquals("DCP membership authentication failed", error.getMessage());
    assertNull(error.getCause());
  }

  @Test
  void rejectsWrongIssuerTypeAndSubject() throws Exception {
    response("did:web:untrusted", HOLDER, "MembershipCredential");
    assertThrows(BadCredentialsException.class, () -> service.authenticateMachineAccess("Bearer si"));
    response(ISSUER, HOLDER, "OtherCredential");
    assertThrows(BadCredentialsException.class, () -> service.authenticateMachineAccess("Bearer si"));
    response(ISSUER, "did:web:other", "MembershipCredential");
    assertThrows(BadCredentialsException.class, () -> service.authenticateMachineAccess("Bearer si"));
    verifyNoInteractions(verification);
  }

  @Test
  void rejectsMissingTrustConfigurationBeforePull() {
    properties.setAudience("");
    assertThrows(AuthenticationServiceException.class, () -> service.authenticateMachineAccess("Bearer si"));
    properties.setAudience("https://catalogue.example");
    properties.getPostAssets().setRequiredIssuer("");
    assertThrows(AuthenticationServiceException.class, () -> service.authenticateMachineAccess("Bearer si"));
    verifyNoInteractions(verifier, verification);
  }

  @Test
  void rejectsMissingOrAmbiguousPresentations() throws Exception {
    var response = response(ISSUER, HOLDER, "MembershipCredential");
    when(verifier.pullForAuthentication(any(), any())).thenReturn(new DcpVerifierService.PulledPresentation(
        HOLDER, new PresentationResponseMessage(response.context(), response.type(), List.of(), null)));
    assertThrows(BadCredentialsException.class, () -> service.authenticateMachineAccess("Bearer si"));
    when(verifier.pullForAuthentication(any(), any())).thenReturn(new DcpVerifierService.PulledPresentation(
        HOLDER, new PresentationResponseMessage(response.context(), response.type(),
            List.of(response.presentation().getFirst(), response.presentation().getFirst()), null)));
    assertThrows(BadCredentialsException.class, () -> service.authenticateMachineAccess("Bearer si"));
    verifyNoInteractions(verification);
  }

  private PresentationResponseMessage response(String issuer, String subject, String type) throws Exception {
    return response(issuer, subject, type, "", "", "");
  }

  private PresentationResponseMessage response(String issuer, String subject, String type,
      String vcClaims, String vcFields, String vpClaims) throws Exception {
    var mapper = new ObjectMapper();
    String vc = jwt("""
        {"iss":"%s", "vc":{
          "type":["VerifiableCredential","%s"], "issuer":"%s", "credentialSubject":{"id":"%s"}%s
        }%s}
        """.formatted(issuer, type, issuer, subject, vcFields, vcClaims));
    var vp = mapper.getNodeFactory().textNode(jwt("""
        {"iss":"%s", "vp":{"type":["VerifiablePresentation"], "verifiableCredential":[%s]}%s}
        """.formatted(HOLDER, mapper.writeValueAsString(vc), vpClaims)));
    var response = new PresentationResponseMessage(List.of("https://w3id.org/dspace-dcp/v1.0/dcp.jsonld"),
        "PresentationResponseMessage", List.of(vp), null);
    when(verifier.pullForAuthentication(any(), any()))
        .thenReturn(new DcpVerifierService.PulledPresentation(HOLDER, response));
    return response;
  }

  // Syntactic JWT fixtures only: the cryptographic verification boundary is mocked explicitly.
  private String jwt(String payload) {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    return encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
        + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".c2ln";
  }

  @ParameterizedTest
  @ValueSource(strings = {"vc-exp", "vp-exp", "vc-nbf", "vp-nbf", "validUntil", "expirationDate",
      "validFrom", "issuanceDate", "malformed-date"})
  void rejectsInvalidValidityDespiteSuccessfulSignatures(String variant) throws Exception {
    String future = Long.toString(Instant.now().plusSeconds(300).getEpochSecond());
    String vcClaims = switch (variant) { case "vc-exp" -> ",\"exp\":1";
      case "vc-nbf" -> ",\"nbf\":" + future; default -> ""; };
    String vpClaims = switch (variant) { case "vp-exp" -> ",\"exp\":1";
      case "vp-nbf" -> ",\"nbf\":" + future; default -> ""; };
    String fields = switch (variant) {
      case "validUntil", "expirationDate" -> ",\"" + variant + "\":\"2000-01-01T00:00:00Z\"";
      case "validFrom", "issuanceDate" -> ",\"" + variant + "\":\"" + Instant.now().plusSeconds(300) + "\"";
      case "malformed-date" -> ",\"validUntil\":42"; default -> "";
    };
    response(ISSUER, HOLDER, "MembershipCredential", vcClaims, fields, vpClaims);
    when(verification.verifyCredential(any(), eq(true), eq(true), eq(true), eq(false)))
        .thenReturn(new CredentialVerificationResult(Instant.now(), "ACTIVE", ISSUER, Instant.now(), HOLDER,
            List.of(), List.of(new Validator(HOLDER + "#key", null, null),
                new Validator(ISSUER + "#key", null, null)), null, null));
    assertThrows(BadCredentialsException.class, () -> service.authenticateMachineAccess("Bearer si"));
  }

  @Test
  void neverAcceptsAnUnverifiedOrNonCredentialResult() throws Exception {
    response(ISSUER, HOLDER, "MembershipCredential");
    assertThrows(BadCredentialsException.class, () -> service.authenticateMachineAccess("Bearer si"));
  }
}
