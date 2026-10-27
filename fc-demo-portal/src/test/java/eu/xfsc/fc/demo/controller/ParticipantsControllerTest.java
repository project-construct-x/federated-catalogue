package eu.xfsc.fc.demo.controller;

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

import static eu.xfsc.fc.demo.utils.CommonConstants.ADMIN_ALL_WITH_PREFIX;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import eu.xfsc.fc.client.ParticipantClient;
import eu.xfsc.fc.demo.config.CustomOidcUserService;
import eu.xfsc.fc.demo.config.SecurityConfig;

@WebMvcTest(ParticipantsController.class)
@Import({
    SecurityConfig.class,
    CustomOidcUserService.class
})
public class ParticipantsControllerTest {
    
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ParticipantClient participantClient;

    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;


    @Test
    void addParticipant_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/parts"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void addParticipant_withoutAdminRole_forbidden() throws Exception {
        mockMvc.perform(post("/parts")
                .with(oauth2Login()))
            .andExpect(status().isForbidden());
    }

    @Test
    void addParticipant_withAdminRole_ok() throws Exception {
        mockMvc.perform(post("/parts")
                .with(adminLogin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
    }


    @Test
    void deleteParticipant_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(delete("/parts/1"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void deleteParticipant_withoutAdminRole_forbidden() throws Exception {
        mockMvc.perform(delete("/parts/1")
                .with(oauth2Login()))
            .andExpect(status().isForbidden());
    }

    @Test
    void deleteParticipant_withAdminRole_ok() throws Exception {
        mockMvc.perform(delete("/parts/1")
                .with(adminLogin()))
            .andExpect(status().isOk());
    }


    @Test
    void getParticipant_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/parts/1"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void getParticipant_withoutAdminRole_forbidden() throws Exception {
        mockMvc.perform(get("/parts/1")
                .with(oauth2Login()))
            .andExpect(status().isForbidden());
    }

    @Test
    void getParticipant_withAdminRole_ok() throws Exception {
        mockMvc.perform(get("/parts/1")
                .with(adminLogin()))
            .andExpect(status().isOk());
    }


    @Test
    void getParticipantUsers_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/parts/1/users"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void getParticipantUsers_withoutAdminRole_forbidden() throws Exception {
        mockMvc.perform(get("/parts/1/users")
                .with(oauth2Login()))
            .andExpect(status().isForbidden());
    }

    @Test
    void getParticipantUsers_withAdminRole_ok() throws Exception {
        mockMvc.perform(get("/parts/1/users")
                .with(adminLogin()))
            .andExpect(status().isOk());
    }


    @Test
    void getParticipants_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/parts"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void getParticipants_withoutAdminRole_forbidden() throws Exception {
        mockMvc.perform(get("/parts")
                .with(oauth2Login()))
            .andExpect(status().isForbidden());
    }

    @Test
    void getParticipants_withAdminRole_ok() throws Exception {
        mockMvc.perform(get("/parts")
                .with(adminLogin()))
            .andExpect(status().isOk());
    }


    @Test
    void updateParticipant_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(put("/parts/1"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void updateParticipant_withoutAdminRole_forbidden() throws Exception {
        mockMvc.perform(put("/parts/1")
                .with(oauth2Login()))
            .andExpect(status().isForbidden());
    }

    @Test
    void updateParticipant_withAdminRole_ok() throws Exception {
        mockMvc.perform(put("/parts/1")
                .with(adminLogin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
    }

    private OAuth2LoginRequestPostProcessor adminLogin() {
        return oauth2Login().authorities(new SimpleGrantedAuthority(ADMIN_ALL_WITH_PREFIX));
    }
}
