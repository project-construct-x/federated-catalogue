package eu.xfsc.fc.server.config;

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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import com.nimbusds.jwt.SignedJWT;
import eu.xfsc.fc.core.service.oid4vp.BootstrapTokenService;

import java.io.IOException;
import java.text.ParseException;

/** Wird in der DCP- und Admin-Chain vorgeschaltet: typ=bootstrap+jwt -> 401 (AK 5). */
public class BootstrapTokenRejectingFilter extends OncePerRequestFilter {
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (isBootstrapToken(request)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Bootstrap tokens are not accepted here");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isBootstrapToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(
                true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return false;
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isBlank()) {
            return false;
        }

        try {
            var type = SignedJWT.parse(token).getHeader().getType();
            return BootstrapTokenService.TOKEN_TYPE.equals(type == null ? null : type.toString());
        } catch (ParseException e) {
            return false;
        }
    }
}
