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

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Shared method/path allowlist for DCP authentication and authorization. */
final class DcpMachineEndpoints {
  private DcpMachineEndpoints() { }

  private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

  static final RequestMatcher MATCHER = new OrRequestMatcher(
      PATHS.matcher(HttpMethod.GET, "/assets"),
      PATHS.matcher(HttpMethod.POST, "/assets"),
      PATHS.matcher(HttpMethod.POST, "/assets/validate"),
      PATHS.matcher(HttpMethod.GET, "/assets/*"),
      PATHS.matcher(HttpMethod.PUT, "/assets/*"),
      PATHS.matcher(HttpMethod.GET, "/assets/*/versions"),
      PATHS.matcher(HttpMethod.POST, "/assets/*/versions/*/revoke"),
      PATHS.matcher(HttpMethod.POST, "/assets/*/human-readable"),
      PATHS.matcher(HttpMethod.PUT, "/assets/*/human-readable"),
      PATHS.matcher(HttpMethod.GET, "/assets/*/human-readable"),
      PATHS.matcher(HttpMethod.GET, "/assets/*/machine-readable"),
      PATHS.matcher(HttpMethod.GET, "/assets/*/validations"),
      PATHS.matcher(HttpMethod.POST, "/assets/*/compliance-check"),
      PATHS.matcher(HttpMethod.GET, "/assets/*/compliance-checks"),
      PATHS.matcher(HttpMethod.POST, "/assets/*/provenance"),
      PATHS.matcher(HttpMethod.GET, "/assets/*/provenance"),
      PATHS.matcher(HttpMethod.POST, "/assets/*/provenance/verify"),
      PATHS.matcher(HttpMethod.GET, "/assets/*/provenance/*"),
      PATHS.matcher(HttpMethod.POST, "/assets/*/provenance/*/verify"),
      PATHS.matcher(HttpMethod.DELETE, "/assets/*"),
      PATHS.matcher(HttpMethod.DELETE, "/assets/by-id/**"),
      PATHS.matcher(HttpMethod.POST, "/assets/*/revoke"),
      PATHS.matcher(HttpMethod.GET, "/validations/*"),
      PATHS.matcher(HttpMethod.GET, "/query"),
      PATHS.matcher(HttpMethod.POST, "/query"),
      PATHS.matcher(HttpMethod.GET, "/query/info"),
      PATHS.matcher(HttpMethod.POST, "/query/search"),
      PATHS.matcher(HttpMethod.GET, "/trust-frameworks"),
      PATHS.matcher(HttpMethod.GET, "/verification"),
      PATHS.matcher(HttpMethod.POST, "/verification"),
      PATHS.matcher(HttpMethod.GET, "/participants"),
      PATHS.matcher(HttpMethod.POST, "/participants"),
      PATHS.matcher(HttpMethod.GET, "/participants/*"),
      PATHS.matcher(HttpMethod.PUT, "/participants/*"),
      PATHS.matcher(HttpMethod.DELETE, "/participants/*"));
}
