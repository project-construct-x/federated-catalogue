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

/**
 * Claims from a validated DCP Self-Issued ID Token.
 *
 * @param holderDid participant DID ({@code sub} / {@code iss})
 * @param audience token {@code aud} (may be null)
 * @param jti unique token id
 * @param opaqueToken optional opaque {@code token} claim to forward to the Credential Service
 * @param rawJwt compact serialization of the validated token
 */
public record ValidatedSelfIssuedIdToken(
    String holderDid,
    String audience,
    String jti,
    String opaqueToken,
    String rawJwt) {
}
