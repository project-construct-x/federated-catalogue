package eu.xfsc.fc.server.util;

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

/**
 * Roles and permissions constant class.
 */
public final class CommonConstants {
  private CommonConstants() {}

  public static final String PREFIX = "ROLE_";
  public static final String ADMIN_ALL = "ADMIN_ALL";

  // Prefixed permission roles (Spring Security ROLE_ prefix)
  public static final String ADMIN_ALL_WITH_PREFIX = PREFIX + ADMIN_ALL;
}
