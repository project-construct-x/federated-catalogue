package eu.xfsc.fc.core.dao.impl;

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

import eu.xfsc.fc.core.dao.ParticipantDao;
import eu.xfsc.fc.core.dao.participants.CatalogueParticipant;
import eu.xfsc.fc.core.exception.ClientException;
import eu.xfsc.fc.core.exception.ConflictException;
import eu.xfsc.fc.core.pojo.PaginatedResults;
import eu.xfsc.fc.core.pojo.ParticipantMetaData;
import eu.xfsc.fc.core.security.DcpParticipantAccess;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Transactional catalogue storage, independent of Keycloak accounts and groups. */
@Repository
@Transactional
public class ParticipantDaoImpl implements ParticipantDao {
  @PersistenceContext
  private EntityManager entityManager;

  @Override
  public ParticipantMetaData create(ParticipantMetaData participant) {
    checkOwner(participant.getId());
    if (entityManager.find(CatalogueParticipant.class, participant.getId()) != null) {
      throw new ConflictException("Participant already exists: " + participant.getId());
    }
    CatalogueParticipant entity = new CatalogueParticipant();
    entity.setDid(participant.getId());
    copy(participant, entity);
    try {
      entityManager.persist(entity);
      entityManager.flush();
    } catch (PersistenceException ex) {
      // A concurrent registration may pass the existence check; the primary key still wins.
      for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
        if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) {
          throw new ConflictException("Participant already exists: " + participant.getId());
        }
      }
      throw ex;
    }
    return metadata(entity);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ParticipantMetaData> select(String participantId) {
    return Optional.ofNullable(entityManager.find(CatalogueParticipant.class, participantId))
        .map(ParticipantDaoImpl::metadata);
  }

  @Override
  public Optional<ParticipantMetaData> selectForUpdate(String participantId) {
    checkOwner(participantId);
    return Optional.ofNullable(entityManager.find(CatalogueParticipant.class, participantId,
        LockModeType.PESSIMISTIC_WRITE)).map(ParticipantDaoImpl::metadata);
  }

  @Override
  public Optional<ParticipantMetaData> update(String participantId, ParticipantMetaData participant) {
    checkOwner(participantId);
    if (!participantId.equals(participant.getId())) {
      throw new ClientException("Participant ID cannot be changed");
    }
    CatalogueParticipant entity = entityManager.find(CatalogueParticipant.class, participantId);
    if (entity == null) {
      return Optional.empty();
    }
    copy(participant, entity);
    entityManager.flush();
    return Optional.of(metadata(entity));
  }

  @Override
  public Optional<ParticipantMetaData> delete(String participantId) {
    checkOwner(participantId);
    CatalogueParticipant entity = entityManager.find(CatalogueParticipant.class, participantId);
    if (entity == null) {
      return Optional.empty();
    }
    ParticipantMetaData result = metadata(entity);
    entityManager.remove(entity);
    entityManager.flush();
    return Optional.of(result);
  }

  /** Administrative catalogue inventory; machine callers are scoped by ParticipantsService. */
  @Override
  @Transactional(readOnly = true)
  public PaginatedResults<ParticipantMetaData> search(Integer offset, Integer limit) {
    if (offset == null || offset < 0 || limit == null || limit < 0) {
      throw new ClientException("Offset must be non-negative and limit must be non-negative");
    }
    long total = entityManager.createQuery("select count(p) from CatalogueParticipant p", Long.class)
        .getSingleResult();
    var results = entityManager.createQuery("select p from CatalogueParticipant p order by p.did",
            CatalogueParticipant.class).setFirstResult(offset).setMaxResults(limit).getResultList();
    return new PaginatedResults<>(total, results.stream().map(ParticipantDaoImpl::metadata).toList());
  }

  private static void checkOwner(String did) {
    DcpParticipantAccess.checkAccess(SecurityContextHolder.getContext().getAuthentication(), did);
  }

  private static void copy(ParticipantMetaData source, CatalogueParticipant target) {
    if (source.getAssetHash() == null || !source.getAssetHash().matches("[a-fA-F0-9]{64}")) {
      throw new ClientException("Participant credential hash must be SHA-256");
    }
    target.setName(source.getName());
    target.setPublicKey(source.getPublicKey());
    target.setAssetHash(source.getAssetHash());
  }

  private static ParticipantMetaData metadata(CatalogueParticipant entity) {
    return new ParticipantMetaData(entity.getDid(), entity.getName(), entity.getPublicKey(), null,
        entity.getAssetHash());
  }
}
