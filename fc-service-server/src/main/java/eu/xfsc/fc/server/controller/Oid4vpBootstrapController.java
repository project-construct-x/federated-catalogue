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
import eu.xfsc.fc.core.service.oid4vp.BootstrapPrincipal;
import eu.xfsc.fc.core.service.oid4vp.BootstrapTokenResponse;
import eu.xfsc.fc.core.service.oid4vp.BootstrapTokenService;
import eu.xfsc.fc.core.service.oid4vp.ConnectorBinding;
import eu.xfsc.fc.core.service.oid4vp.ConnectorBindingService;
import eu.xfsc.fc.core.service.oid4vp.Oid4vpDirectPostHandler;
import eu.xfsc.fc.core.service.oid4vp.Oid4vpPresentationRequestService;
import eu.xfsc.fc.core.service.oid4vp.PresentationRequestResult;
import java.nio.file.AccessDeniedException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the OID4VP bootstrap flow for connector onboarding.
 *
 * <p>The flow consists of presentation request creation, wallet direct-post handling, bootstrap
 * token redemption and final connector binding using the bootstrap principal.
 */
@RestController
@RequestMapping("/api/auth/oid4vp")
@RequiredArgsConstructor
public class Oid4vpBootstrapController {
    private final Oid4vpPresentationRequestService requests;
    private final Oid4vpDirectPostHandler directPost;
    private final BootstrapTokenService tokens;
    private final ConnectorBindingService binding;

    /**
     * Creates an OID4VP presentation request for the connector DID.
     *
     * @param connectorDid connector DID that starts the bootstrap flow
     * @return request metadata that can be handed to a wallet
     */
    @PostMapping("/requests")
    public PresentationRequestResult request(@RequestParam String connectorDid) {
        return requests.createBootstrapRequest(connectorDid);
    }

    /**
     * Receives the wallet direct-post response and lets the OID4VP library issue a response code.
     *
     * @param vpToken submitted {@code vp_token} form field
     * @param state submitted OID4VP state form field
     * @return direct-post response containing the response code
     */
    @PostMapping(value = "/direct-post", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public VpTokenResponse.DirectPostResponse directPost(@RequestParam("vp_token") String vpToken,
                                                         @RequestParam String state) {
        return directPost.handle(vpToken, state);
    }

    /**
     * Redeems a response code for a short-lived catalogue-local bootstrap token.
     *
     * @param state OID4VP state originally associated with the presentation request
     * @param code response code returned by the direct-post step
     * @return bootstrap bearer token for the connector binding endpoint
     */
    @PostMapping("/token")
    public BootstrapTokenResponse token(@RequestParam String state,
                                        @RequestParam("response_code") String code) {
        return tokens.redeem(state, code);
    }

    /**
     * Creates the durable connector binding for an authenticated bootstrap principal.
     *
     * @param connectorDid connector DID from the path
     * @param principal authenticated bootstrap principal injected by the security filter
     * @return persisted or idempotently returned connector binding
     * @throws AccessDeniedException if the principal was issued for a different connector DID
     */
    @PostMapping("/connectors/{connectorDid}/bind")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectorBinding bind(@PathVariable String connectorDid,
                                 @AuthenticationPrincipal BootstrapPrincipal principal) throws AccessDeniedException {
        return binding.bind(principal, connectorDid);
    }
}
