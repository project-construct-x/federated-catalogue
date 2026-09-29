package eu.xfsc.fc.server.controller;

/*-
 * ---license-start
 * fc-service-server
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

import de.eecc.oid4vc.oid4vp.VpTokenResponse;
import eu.xfsc.fc.core.service.oid4vp.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.file.AccessDeniedException;

@RestController
@RequestMapping("/api/auth/oid4vp")
@RequiredArgsConstructor
public class Oid4vpBootstrapController {
    private final Oid4vpPresentationRequestService requests;
    private final Oid4vpDirectPostHandler directPost;
    private final BootstrapTokenService tokens;
    private final ConnectorBindingService binding;

    @PostMapping("/requests")
    public PresentationRequestResult request(@RequestParam String connectorDid) {
        return requests.createBootstrapRequest(connectorDid);
    }

    @PostMapping(value = "/direct-post", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public VpTokenResponse.DirectPostResponse directPost(@RequestParam("vp_token") String vpToken,
                                                         @RequestParam String state) {
        return directPost.handle(vpToken, state);
    }

    @PostMapping("/token")
    public BootstrapTokenResponse token(@RequestParam String state,
                                        @RequestParam("response_code") String code) {
        return tokens.redeem(state, code);
    }

    @PostMapping("/connectors/{connectorDid}/bind")
    public ConnectorBinding bind(@PathVariable String connectorDid,
                                 @AuthenticationPrincipal BootstrapPrincipal principal) throws AccessDeniedException {
        return binding.bind(principal, connectorDid);
    }
}
