package eu.xfsc.fc.demo.config;

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

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;

import static eu.xfsc.fc.demo.utils.CommonConstants.ADMIN_ALL;

/**
 * Specifies the application's security configuration.
 */
@Configuration
@EnableWebSecurity //(debug = true)
public class SecurityConfig { 

    private final OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService;

    public SecurityConfig(OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService) {
        this.oidcUserService = oidcUserService;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
          .csrf(c -> c.disable())
          .authorizeHttpRequests(authorization -> authorization
        		  .requestMatchers(antMatcher("/")).permitAll()
        		  .requestMatchers(antMatcher("/index.html")).permitAll()
        		  .requestMatchers(antMatcher("/css/**")).permitAll()
        		  .requestMatchers(antMatcher("/js/**")).permitAll()
		  .anyRequest().hasRole(ADMIN_ALL)
       	  )
          .oauth2Login(oauth2Login ->
              oauth2Login
                  .loginPage("/oauth2/authorization/fc-client-oidc")
                  .userInfoEndpoint((userInfo) -> userInfo
                    .oidcUserService(this.oidcUserService)
            )
          )
          .oauth2Client(Customizer.withDefaults())
          		.logout(c -> c.logoutRequestMatcher(antMatcher(HttpMethod.POST, "/logout"))
          			.logoutSuccessUrl("/index.html")
          			.clearAuthentication(true)
          			.invalidateHttpSession(true)
          			.deleteCookies("JSESSIONID","XSRF-TOKEN"));
        return http.build();
    }
       
}
