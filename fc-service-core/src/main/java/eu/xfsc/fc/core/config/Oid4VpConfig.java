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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Oid4VpConfig {

    @Bean
    public Oid4Vp oid4Vp(Oid4vpBootstrapProperties props) {
        // WICHTIG: hier die EECC-API-Fabrikmethode oder den Konstruktor einsetzen,
        // die in der Bibliothek tatsächlich existiert.
        // Beispiel-Form:
        // return new Oid4Vp(props.getVerifierUrl(), props.getClientId(), props.getResponseUri());
        // oder:
        // return Oid4VpFactory.createDefault(props.getVerifierUrl(), props.getClientId());
        Oid4Vp oid4Vp = Oid4Vp.create(Oid4VpOptions.builder()
                .verifierUrl(/*"http://vc-verifier:3000/api/verifier"*/props.getVerifierUrl()) // recommended: EECC VC Verifier (see link above)
                .responseUri("https://example.com/api/auth/oid4vp/response")
                .redirectUri("https://example.com/login/oid4vp")
                .requestUriBaseUrl("https://example.com/api/auth/oid4vp")
                .requestUriEnabled(true)
                .build());

//        throw new UnsupportedOperationException(
//                "Use the actual EECC Oid4Vp factory/constructor from the library API here");
        return oid4Vp;
    }
}
