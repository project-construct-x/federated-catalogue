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

import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Shared method/path allowlist for DCP authentication and authorization. */
final class DcpMachineEndpoints {
  private DcpMachineEndpoints() { }

  static final RequestMatcher MATCHER = new OrRequestMatcher(
      new AntPathRequestMatcher("/assets", "GET"),
      new AntPathRequestMatcher("/assets", "POST"),
      new AntPathRequestMatcher("/assets/validate", "POST"),
      new AntPathRequestMatcher("/assets/*", "GET"),
      new AntPathRequestMatcher("/assets/*", "PUT"),
      new AntPathRequestMatcher("/assets/*/versions", "GET"),
      new AntPathRequestMatcher("/assets/*/versions/*/revoke", "POST"),
      new AntPathRequestMatcher("/assets/*/human-readable", "POST"),
      new AntPathRequestMatcher("/assets/*/human-readable", "PUT"),
      new AntPathRequestMatcher("/assets/*/human-readable", "GET"),
      new AntPathRequestMatcher("/assets/*/machine-readable", "GET"),
      new AntPathRequestMatcher("/assets/*/validations", "GET"),
      new AntPathRequestMatcher("/assets/*/compliance-check", "POST"),
      new AntPathRequestMatcher("/assets/*/compliance-checks", "GET"),
      new AntPathRequestMatcher("/assets/*/provenance", "POST"),
      new AntPathRequestMatcher("/assets/*/provenance", "GET"),
      new AntPathRequestMatcher("/assets/*/provenance/verify", "POST"),
      new AntPathRequestMatcher("/assets/*/provenance/*", "GET"),
      new AntPathRequestMatcher("/assets/*/provenance/*/verify", "POST"),
      new AntPathRequestMatcher("/assets/*", "DELETE"),
      new AntPathRequestMatcher("/assets/by-id/**", "DELETE"),
      new AntPathRequestMatcher("/assets/*/revoke", "POST"),
      new AntPathRequestMatcher("/validations/*", "GET"),
      new AntPathRequestMatcher("/query", "GET"),
      new AntPathRequestMatcher("/query", "POST"),
      new AntPathRequestMatcher("/query/info", "GET"),
      new AntPathRequestMatcher("/query/search", "POST"),
      new AntPathRequestMatcher("/trust-frameworks", "GET"),
      new AntPathRequestMatcher("/verification", "GET"),
      new AntPathRequestMatcher("/verification", "POST"),
      new AntPathRequestMatcher("/participants", "GET"),
      new AntPathRequestMatcher("/participants", "POST"),
      new AntPathRequestMatcher("/participants/*", "GET"),
      new AntPathRequestMatcher("/participants/*", "PUT"),
      new AntPathRequestMatcher("/participants/*", "DELETE"));
}
