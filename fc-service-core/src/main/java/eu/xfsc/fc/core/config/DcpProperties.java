package eu.xfsc.fc.core.config;

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

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.stereotype.Component;

/**
 * Configuration for the catalogue acting as a DCP verifier
 * ({@code POST /dcp/presentations} and DCP presentation ingest on asset write).
 */
@Getter
@Setter
@Component
@ConfigurationProperties("federated-catalogue.dcp")
public class DcpProperties {

  /** Catalogue verifier DID ({@code iss}/{@code sub} of minted verifier SI tokens). */
  private String verifierDid = "";

  /** Expected {@code aud} claim on inbound client Self-Issued ID Tokens. Empty = skip aud check. */
  private String audience = "";

  /** Private JWK JSON used to sign verifier Self-Issued ID Tokens. */
  private String verifierSigningKey = "";

  /** Lifetime of minted verifier Self-Issued ID Tokens. */
  private Duration verifierTokenTtl = Duration.ofMinutes(10);

  /** TTL for seen SI token {@code jti} values (replay protection). */
  private Duration jtiTtl = Duration.ofHours(1);

  /** Timeout for outbound Credential Service HTTP calls. */
  private Duration httpTimeout = Duration.ofSeconds(10);

  /**
   * Constraints applied when verifying presentations for {@code POST_ASSETS}.
   * Empty issuer / credential-type values omit the corresponding check.
   */
  @NestedConfigurationProperty
  private PostAssets postAssets = new PostAssets();

  @Getter
  @Setter
  public static class PostAssets {

    /**
     * Required VC issuer DID for asset write presentations.
     * Empty = omit issuer check.
     */
    private String requiredIssuer = "";

    /**
     * Required VC type for asset write presentations (e.g. {@code MembershipCredential}).
     * Empty = omit credential-type check (DB presentation request definition still applies).
     */
    private String requiredCredentialType = "";
  }
}
