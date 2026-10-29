package eu.xfsc.fc.core.dao.participants;

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

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.envers.Audited;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** Catalogue participant metadata; credentials remain in the asset store. */
@Entity
@Table(name = "catalogue_participants")
@Audited
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class CatalogueParticipant {
  @Id
  @Column(name = "did", columnDefinition = "TEXT")
  private String did;
  @Column(name = "name", columnDefinition = "TEXT")
  private String name;
  @Column(name = "public_key", columnDefinition = "TEXT")
  private String publicKey;
  @Column(name = "asset_hash", length = 64, nullable = false)
  private String assetHash;
  @Version
  private long version;
  @CreatedBy
  @Column(name = "created_by", columnDefinition = "TEXT", updatable = false)
  private String createdBy;
  @LastModifiedBy
  @Column(name = "modified_by", columnDefinition = "TEXT")
  private String modifiedBy;
}
