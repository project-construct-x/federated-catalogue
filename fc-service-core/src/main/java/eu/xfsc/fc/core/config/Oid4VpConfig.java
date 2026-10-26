package eu.xfsc.fc.core.config;

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

import de.eecc.oid4vc.oid4vp.api.Oid4Vp;
import de.eecc.oid4vc.oid4vp.api.Oid4VpOptions;
import eu.xfsc.fc.core.service.oid4vp.Oid4vpBootstrapProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(Oid4vpBootstrapProperties.class)
public class Oid4VpConfig {

    @Bean
    public Oid4Vp oid4Vp(Oid4vpBootstrapProperties props) {
        System.out.println("OID4VP enabled = " + props.isEnabled());
        System.out.println("OID4VP requestUriBaseUrl = "
                + props.getRequestUriBaseUrl());

        Oid4Vp oid4Vp = Oid4Vp.create(Oid4VpOptions.builder()
                .verifierUrl(/*"http://vc-verifier:3000/api/verifier"*/props.getVerifierUrl()) // recommended: EECC VC Verifier (see link above)
                .responseUri(/*"http://localhost:8081/api/auth/oid4vp/direct-post*/props.getResponseUri()) // recommended: local direct-post endpoint")
                .redirectUri(/*"http://localhost:8081/api/auth/oid4vp/direct-post"*/props.getRedirectUri()) // recommended: local direct-post endpoint")
                .requestUriBaseUrl(/*"http://localhost:8081/api/auth/oid4vp"*/props.getRequestUriBaseUrl()) // recommended: local request endpoint")
                .requestTtl(props.getRequestTtl())
                .requestUriEnabled(props.isEnabled())
                .build());

        return oid4Vp;
    }
}
