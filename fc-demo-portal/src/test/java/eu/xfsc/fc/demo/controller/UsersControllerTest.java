package eu.xfsc.fc.demo.controller;

import static eu.xfsc.fc.demo.utils.CommonConstants.ADMIN_ALL_WITH_PREFIX;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import eu.xfsc.fc.client.UserClient;
import eu.xfsc.fc.demo.config.CustomOidcUserService;
import eu.xfsc.fc.demo.config.SecurityConfig;

@WebMvcTest(UsersController.class)
@Import({
    SecurityConfig.class,
    CustomOidcUserService.class
})
public class UsersControllerTest {

    @Autowired
    private MockMvc mockMvc;
    
    @MockitoBean
    private UserClient userClient;

    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;
    
    @Test
    void addUser_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/users"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void addUser_withoutAdminRole_forbidden() throws Exception {
        mockMvc.perform(post("/users")
                .with(oauth2Login()))
            .andExpect(status().isForbidden());
    }

    @Test
    void addUser_withAdminRole_ok() throws Exception {
        mockMvc.perform(post("/users")
                .with(adminLogin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
    }


    @Test
    void updateUser_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(put("/users/1"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void updateUser_withoutAdminRole_forbidden() throws Exception {
        mockMvc.perform(put("/users/1")
                .with(oauth2Login()))
            .andExpect(status().isForbidden());
    }

    @Test
    void updateUser_withAdminRole_ok() throws Exception {
        mockMvc.perform(put("/users/1")
                .with(adminLogin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
    }


    @Test
    void deleteUser_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(delete("/users/1"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void deleteUser_withoutAdminRole_forbidden() throws Exception {
        mockMvc.perform(delete("/users/1")
                .with(oauth2Login()))
            .andExpect(status().isForbidden());
    }

    @Test
    void deleteUser_withAdminRole_ok() throws Exception {
        mockMvc.perform(delete("/users/1")
                .with(adminLogin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
    }


    @Test
    void getUser_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/users/1"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void getUser_withoutAdminRole_forbidden() throws Exception {
        mockMvc.perform(get("/users/1")
                .with(oauth2Login()))
            .andExpect(status().isForbidden());
    }

    @Test
    void getUser_withAdminRole_ok() throws Exception {
        mockMvc.perform(get("/users/1")
                .with(adminLogin()))
            .andExpect(status().isOk());
    }


    @Test
    void getUsers_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/users"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void getUsers_withoutAdminRole_forbidden() throws Exception {
        mockMvc.perform(get("/users")
                .with(oauth2Login()))
            .andExpect(status().isForbidden());
    }

    @Test
    void getUsers_withAdminRole_ok() throws Exception {
        mockMvc.perform(get("/users")
                .with(adminLogin()))
            .andExpect(status().isOk());
    }


    @Test
    void getUserRoles_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/users/1/roles"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void getUserRoles_withoutAdminRole_forbidden() throws Exception {
        mockMvc.perform(get("/users/1/roles")
                .with(oauth2Login()))
            .andExpect(status().isForbidden());
    }

    @Test
    void getUserRoles_withAdminRole_ok() throws Exception {
        mockMvc.perform(get("/users/1/roles")
                .with(adminLogin()))
            .andExpect(status().isOk());
    }


    @Test
    void updateUserRoles_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(put("/users/1/roles"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void updateUserRoles_withoutAdminRole_forbidden() throws Exception {
        mockMvc.perform(put("/users/1/roles")
                .with(oauth2Login()))
            .andExpect(status().isForbidden());
    }

    @Test
    void updateUserRoles_withAdminRole_ok() throws Exception {
        mockMvc.perform(put("/users/1/roles")
                .with(adminLogin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
            .andExpect(status().isOk());
    }

    private OAuth2LoginRequestPostProcessor adminLogin() {
        return oauth2Login().authorities(new SimpleGrantedAuthority(ADMIN_ALL_WITH_PREFIX));
    }
}
