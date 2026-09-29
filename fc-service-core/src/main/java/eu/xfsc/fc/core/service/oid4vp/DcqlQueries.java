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

import java.util.List;
import java.util.Map;

public final class DcqlQueries {
    private DcqlQueries() {
    }

    public static Map<String, Object> membership(List<String> trustedIssuers, boolean requireCredentialStatus) {
        return Map.of(
                "credentials", List.of(
                        Map.of(
                                "id", "membership_credential",
                                "format", "dc+json",
                                "meta", Map.of(
                                        "type", "MembershipCredential"
                                ),
                                "constraints", Map.of(
                                        "fields", List.of(
                                                Map.of(
                                                        "path", "[]",
                                                        "filter", Map.of(
                                                                "type", "string",
                                                                "pattern", ".*"
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );
    }
}
