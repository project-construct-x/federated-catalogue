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

/**
 * Response returned by the catalogue-local OID4VP bootstrap token endpoint.
 *
 * <p>The access token is scoped to connector binding and must only be accepted by the dedicated
 * bootstrap security filter chain.
 *
 * @param accessToken signed catalogue-local bootstrap token
 * @param tokenType HTTP authorization scheme, usually {@code Bearer}
 * @param expiresIn token lifetime in seconds
 */
public record BootstrapTokenResponse(
    String accessToken,
    String tokenType,
    long expiresIn
) {
}

