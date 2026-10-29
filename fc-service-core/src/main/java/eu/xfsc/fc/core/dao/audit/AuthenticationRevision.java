package eu.xfsc.fc.core.dao.audit;

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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.envers.RevisionEntity;
import org.hibernate.envers.RevisionNumber;
import org.hibernate.envers.RevisionTimestamp;

/**
 * Authentication metadata for a committed Envers revision. Operation (ADD/MOD/DEL) and
 * resource identity belong to the associated audit rows, as one revision may affect many resources.
 * Historical revisions and non-DCP operations have no DCP attribution.
 */
@Entity
@Table(name = "revinfo")
@RevisionEntity(DcpRevisionListener.class)
@Getter
@Setter
public class AuthenticationRevision {
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "audit_revision")
  @SequenceGenerator(name = "audit_revision", sequenceName = "revinfo_seq", allocationSize = 50)
  @RevisionNumber
  @Column(name = "rev")
  private int id;

  @RevisionTimestamp
  @Column(name = "revtstmp")
  private long timestamp;

  @Column(name = "participant_did", columnDefinition = "text")
  private String participantDid;

  @Column(name = "actor_did", columnDefinition = "text")
  private String actorDid;

  @Column(name = "authentication_method", length = 32)
  private String authenticationMethod;
}
