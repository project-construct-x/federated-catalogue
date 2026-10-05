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

import static eu.xfsc.fc.server.util.CommonConstants.ADMIN_ALL;
import static eu.xfsc.fc.server.util.CommonConstants.ADMIN_ALL_WITH_PREFIX;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Grants only the explicit application administrator role of the configured Keycloak client. */
@Slf4j
public class CustomJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
  private final String resourceId;

  public CustomJwtAuthenticationConverter(String resourceId) {
    this.resourceId = resourceId;
  }

  @Override
  public AbstractAuthenticationToken convert(Jwt source) {
    return new JwtAuthenticationToken(source, extractAdminRole(source));
  }

  private Collection<GrantedAuthority> extractAdminRole(Jwt jwt) {
    Object claim = jwt.getClaims().get("resource_access");
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
