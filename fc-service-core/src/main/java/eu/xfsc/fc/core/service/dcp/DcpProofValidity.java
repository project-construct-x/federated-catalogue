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

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Instant;

/** Authentication validity checks independent of ingest-specific semantic validation toggles. */
final class DcpProofValidity {
  private DcpProofValidity() { }

  static void check(JWTClaimsSet claims, JsonNode credential, Instant now) {
    if (claims.getExpirationTime() != null && !claims.getExpirationTime().toInstant().isAfter(now)) {
      throw new IllegalArgumentException("Expired DCP proof");
    }
    if (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().isAfter(now)) {
      throw new IllegalArgumentException("DCP proof not yet valid");
    }
    if (claims.getIssueTime() != null && claims.getIssueTime().toInstant().isAfter(now)) {
      throw new IllegalArgumentException("DCP proof issued in the future");
    }
    if (credential != null) {
      checkDate(credential, "validFrom", false, now);
      checkDate(credential, "issuanceDate", false, now);
      checkDate(credential, "validUntil", true, now);
      checkDate(credential, "expirationDate", true, now);
    }
  }

  private static void checkDate(JsonNode credential, String name, boolean expiry, Instant now) {
    if (!credential.has(name)) {
      return;
    }
    JsonNode field = credential.get(name);
    if (!field.isTextual()) {
      throw new IllegalArgumentException("Invalid credential validity date");
    }
    Instant value = Instant.parse(field.asText());
    if (expiry ? !value.isAfter(now) : value.isAfter(now)) {
      throw new IllegalArgumentException("Credential outside validity period");
    }
  }
}