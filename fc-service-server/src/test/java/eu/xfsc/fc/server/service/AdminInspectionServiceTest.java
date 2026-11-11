package eu.xfsc.fc.server.service;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import eu.xfsc.fc.api.generated.model.AssetStatus;
import eu.xfsc.fc.api.generated.model.QueryLanguage;
import eu.xfsc.fc.core.exception.ClientException;
import eu.xfsc.fc.core.pojo.GraphBackendType;
import eu.xfsc.fc.core.pojo.PaginatedResults;
import eu.xfsc.fc.core.service.assetstore.AssetRecord;
import eu.xfsc.fc.core.service.assetstore.AssetStore;
import eu.xfsc.fc.core.service.graphdb.GraphStore;

class AdminInspectionServiceTest {
  private AssetStore assetStore;
  private GraphStore graphStore;
  private QueryLanguageValidator validator;
  private AdminInspectionService service;

  @BeforeEach
  void admin() {
    assetStore = org.mockito.Mockito.mock(AssetStore.class);
    graphStore = org.mockito.Mockito.mock(GraphStore.class);
    validator = org.mockito.Mockito.mock(QueryLanguageValidator.class);
    service = new AdminInspectionService(assetStore, graphStore, validator);
    Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("admin").build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
        jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN_ALL"))));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void postedAssetsComeFromTheCatalogueStore() {
    AssetRecord asset = org.mockito.Mockito.mock(AssetRecord.class);
    when(asset.getId()).thenReturn("https://example.org/asset");
    when(asset.getAssetHash()).thenReturn("abc");
    when(asset.getStatus()).thenReturn(AssetStatus.ACTIVE);
    when(asset.getIssuer()).thenReturn("did:example:issuer");
    when(asset.getContentType()).thenReturn("application/ld+json");
    when(asset.getFileSize()).thenReturn(12L);
    when(asset.getUploadDatetime()).thenReturn(Instant.parse("2026-01-02T03:04:05Z"));
    when(asset.getStatusDatetime()).thenReturn(null);
    when(assetStore.getByFilter(any(), org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.eq(false)))
        .thenReturn(new PaginatedResults<>(1, List.of(asset)));

    var page = service.postedAssets(0, 50);

    assertEquals(1, page.totalCount());
    assertEquals("https://example.org/asset", page.items().getFirst().id());
    assertEquals("ACTIVE", page.items().getFirst().status());
    assertEquals("2026-01-02T03:04:05Z", page.items().getFirst().uploadDatetime());
  }

  @Test
  void postedContentReturnsTextAndMarksBinary() {
    AssetRecord text = org.mockito.Mockito.mock(AssetRecord.class);
    when(text.getId()).thenReturn("https://example.org/json");
    when(text.getContentType()).thenReturn("application/ld+json");
    when(text.getContent()).thenReturn("{\"a\":1}");
    when(assetStore.getByFilter(any(), org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.eq(true)))
        .thenReturn(new PaginatedResults<>(1, List.of(text)));

    var body = service.postedContent("https://example.org/json");

    assertEquals("{\"a\":1}", body.content());
    assertFalse(body.binary());
  }

  @Test
  void queryRunsAReadAndRejectsAWrite() {
    when(graphStore.queryData(any())).thenReturn(new PaginatedResults<>(1, List.of(Map.of("s", "http://example.org/s"))));

    var results = service.query("SELECT ?s WHERE { ?s ?p \"DELETE me\" }", "application/sparql-query");

    assertEquals(1, results.getTotalCount());
    verify(validator).validateLanguageSupport(QueryLanguage.SPARQL);
    assertThrows(ClientException.class,
        () -> service.query("INSERT DATA { <http://example.org/s> <http://example.org/p> <http://example.org/o> }",
            "application/sparql-query"));
    verify(graphStore, never()).queryData(org.mockito.ArgumentMatchers.argThat(
        query -> query.getQuery().contains("INSERT")));
  }

  @Test
  void queryInfoReportsTheActiveBackend() {
    when(graphStore.getBackendType()).thenReturn(GraphBackendType.FUSEKI);
    when(graphStore.getSupportedQueryLanguage()).thenReturn(Optional.of(QueryLanguage.SPARQL));

    var info = service.queryInfo();

    assertTrue(info.getEnabled());
    assertEquals("FUSEKI", info.getBackend());
    assertEquals("application/sparql-query", info.getContentType());
  }

  @Test
  void quotedWriteWordsAreNotTreatedAsWrites() {
    assertFalse(AdminInspectionService.isWriteQuery("SELECT ?s WHERE { ?s ?p \"DELETE\" }"));
    assertTrue(AdminInspectionService.isWriteQuery("DELETE WHERE { ?s ?p ?o }"));
  }

  @Test
  void rowCapAppliesToSelectAndMatchOnly() {
    assertEquals("ASK { ?s ?p ?o }", AdminInspectionService.capReadQuery("ASK { ?s ?p ?o }"));
    assertEquals("SELECT ?s WHERE { ?s ?p ?o } LIMIT 100",
        AdminInspectionService.capReadQuery("SELECT ?s WHERE { ?s ?p ?o }"));
    assertEquals("MATCH (n) RETURN n LIMIT 10",
        AdminInspectionService.capReadQuery("MATCH (n) RETURN n LIMIT 10"));
  }
}
