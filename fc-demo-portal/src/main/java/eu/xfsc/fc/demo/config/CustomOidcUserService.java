package eu.xfsc.fc.demo.config;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration.ProviderDetails;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.util.StringUtils;

import com.nimbusds.jwt.SignedJWT;

import lombok.extern.slf4j.Slf4j;

import static eu.xfsc.fc.demo.utils.CommonConstants.ADMIN_ALL;
import static eu.xfsc.fc.demo.utils.CommonConstants.ADMIN_ALL_WITH_PREFIX;

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

@Slf4j
@Configuration
public class CustomOidcUserService {
    
	@Bean 
	OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService() {
		final OidcUserService delegate = new OidcUserService();

		return (userRequest) -> {
			// Delegate to the default implementation for loading a user
			OidcUser oidcUser = delegate.loadUser(userRequest);

			Set<GrantedAuthority> mappedAuthorities = new HashSet<>();
			log.info("der user: {}", userRequest.getClientRegistration());
			try {
				String resourceId = userRequest.getClientRegistration().getClientId();
				SignedJWT jwt = SignedJWT.parse(userRequest.getAccessToken().getTokenValue());
				Map<String, Object> claims = jwt.getJWTClaimsSet().getClaims();
				Collection<GrantedAuthority> authorities = extractAdminRole(claims, resourceId);
				mappedAuthorities.addAll(authorities);
			}

			catch (Exception e) {
				log.error("Failed to extract role from access token", e);
			}

			ProviderDetails providerDetails = userRequest.getClientRegistration().getProviderDetails();
			String userNameAttributeName = providerDetails.getUserInfoEndpoint().getUserNameAttributeName();
			if (StringUtils.hasText(userNameAttributeName)) {
				oidcUser = new DefaultOidcUser(mappedAuthorities, oidcUser.getIdToken(), oidcUser.getUserInfo(), userNameAttributeName);
			} else {
				oidcUser = new DefaultOidcUser(mappedAuthorities, oidcUser.getIdToken(), oidcUser.getUserInfo());
			}

			return oidcUser;
		};
	}

	Collection<GrantedAuthority> extractAdminRole(Map<String, Object> claims, String resourceId) {
		Object claim = claims.get("resource_access");
		if (!(claim instanceof Map<?, ?> clients)) {
			return List.of();
		}
		if (!clients.containsKey(resourceId)) {
			log.warn("resource_access has no entry for configured resource id '{}'; clients present: {}",
			resourceId, clients.keySet());
			return List.of();
		}
		if (clients.get(resourceId) instanceof Map<?, ?> client
			&& client.get("roles") instanceof Collection<?> roles
			&& roles.contains(ADMIN_ALL)) {
			return List.of(new SimpleGrantedAuthority(ADMIN_ALL_WITH_PREFIX));
		}
		// Neither legacy realm roles nor OAuth scopes grant catalogue capabilities.
		return List.of();
	}
}