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
import de.eecc.dcp.Constants;
import de.eecc.dcp.message.PresentationResponseMessage;
import java.util.List;

/**
 * A DCP {@link PresentationResponseMessage} that passed catalogue request-definition checks,
 * with each {@code presentation[]} entry materialised for the existing credential ingest pipeline.
 */
public record ValidatedDcpPresentation(
    PresentationResponseMessage response,
    List<PresentationPayload> presentations) {

  /**
   * One entry from {@code PresentationResponseMessage.presentation} ready for
   * {@code VerificationService} / asset store.
   */
  public record PresentationPayload(byte[] content, String contentType, JsonNode raw) {}

  public static boolean isPresentationResponseType(JsonNode typeNode) {
    if (typeNode == null || typeNode.isNull()) {
      return false;
    }
    if (typeNode.isTextual()) {
      return Constants.MESSAGE_TYPE_PRESENTATION_RESPONSE.equals(typeNode.asText());
    }
    if (typeNode.isArray()) {
      for (JsonNode entry : typeNode) {
        if (entry != null && Constants.MESSAGE_TYPE_PRESENTATION_RESPONSE.equals(entry.asText())) {
          return true;
        }
      }
    }
    return false;
  }
}
