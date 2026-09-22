package eu.xfsc.fc.core.security;

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

import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** Authentication created only by the DCP boundary after all verification checks succeed. */
public final class DcpAuthenticationToken extends AbstractAuthenticationToken {
  private final DcpIdentity identity;

  /** Takes verified identity data only; no bearer tokens or presentations are retained. */
  public DcpAuthenticationToken(DcpIdentity identity) {
    super(List.of());
    this.identity = Objects.requireNonNull(identity);
    super.setAuthenticated(true);
  }

  @Override
  public DcpIdentity getPrincipal() {
    return identity;
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public String getName() {
    return identity.participantDid();
  }

  @Override
  public void setAuthenticated(boolean authenticated) {
    if (authenticated) {
      throw new IllegalArgumentException("Create a new token after DCP verification");
    }
    super.setAuthenticated(false);
  }
}
