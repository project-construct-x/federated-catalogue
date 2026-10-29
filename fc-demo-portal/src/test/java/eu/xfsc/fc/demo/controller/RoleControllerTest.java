package eu.xfsc.fc.demo.controller;

import static eu.xfsc.fc.demo.utils.CommonConstants.ADMIN_ALL_WITH_PREFIX;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/*-
 * ---license-start
 * fc-demo-portal
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

import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import eu.xfsc.fc.client.RoleClient;
import eu.xfsc.fc.demo.config.CustomOidcUserService;
import eu.xfsc.fc.demo.config.SecurityConfig;

@WebMvcTest(RoleController.class)
@Import({
    SecurityConfig.class,
    CustomOidcUserService.class
})
public class RoleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoleClient roleClient;

    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @Test
    void getAllRoles_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/roles"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void getAllRoles_withoutAdminRole_forbidden() throws Exception {
        mockMvc.perform(get("/roles")
                .with(oauth2Login()))
            .andExpect(status().isForbidden());
    }

    @Test
    void getAllRoles_withAdminRole_ok() throws Exception {
        mockMvc.perform(get("/roles")
                .with(adminLogin()))
            .andExpect(status().isOk());
    }

    private OAuth2LoginRequestPostProcessor adminLogin() {
        return oauth2Login().authorities(new SimpleGrantedAuthority(ADMIN_ALL_WITH_PREFIX));
    }
}
