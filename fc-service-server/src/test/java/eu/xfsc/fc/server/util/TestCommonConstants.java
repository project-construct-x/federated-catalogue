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


public class TestCommonConstants {
  // Legacy role names retained only as test inputs.
  public static final String ASSET_CREATE = "ASSET_CREATE";
  public static final String ASSET_READ = "ASSET_READ";
  public static final String ASSET_UPDATE = "ASSET_UPDATE";
  public static final String ASSET_DELETE = "ASSET_DELETE";
  public static final String ASSET_ADMIN_ROLE = "Ro-AS-A";
  public static final String DEFAULT_PARTICIPANT_ID = "https://issuers/particiant123";
  public static final String CATALOGUE_ADMIN_USERNAME = "catalog_admin";

  // Prefixed permission roles (for @WithMockJwtAuth authorities)
  public static final String PREFIX = "ROLE_";
  public static final String ASSET_ADMIN_ROLE_WITH_PREFIX = PREFIX + ASSET_ADMIN_ROLE;
  public static final String ASSET_CREATE_WITH_PREFIX = PREFIX + ASSET_CREATE;
  public static final String ASSET_READ_WITH_PREFIX = PREFIX + ASSET_READ;
  public static final String ASSET_UPDATE_WITH_PREFIX = PREFIX + ASSET_UPDATE;
  public static final String ASSET_DELETE_WITH_PREFIX = PREFIX + ASSET_DELETE;
}
