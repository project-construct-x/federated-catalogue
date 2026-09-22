package eu.xfsc.fc.core.dao.dcp;

/*-
 * ---license-start
 * fc-service-core
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
 * How a stored presentation request definition maps onto a
 * {@code de.eecc.dcp.query.PresentationQueryDefinition}.
 */
public enum DcpQueryKind {
  /** Scope-based query ({@code ScopeQueryDefinition}). */
  SCOPE,
  /** Presentation Exchange query ({@code PresentationExchangeQueryDefinition}). */
  PRESENTATION_EXCHANGE,
  /** Construct-X MembershipCredential template ({@code MembershipQueryDefinition}). */
  MEMBERSHIP
}
