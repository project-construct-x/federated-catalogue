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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import eu.xfsc.fc.api.generated.model.AssetStatus;
import eu.xfsc.fc.api.generated.model.QueryLanguage;
import de.eecc.dcp.message.PresentationResponseMessage;
import eu.xfsc.fc.core.exception.ClientException;
import eu.xfsc.fc.core.pojo.CredentialVerificationResult;
import eu.xfsc.fc.core.pojo.GraphBackendType;
import eu.xfsc.fc.core.pojo.NonCredentialVerificationResult;
import eu.xfsc.fc.core.pojo.PaginatedResults;
import eu.xfsc.fc.core.service.assetstore.AssetRecord;
import eu.xfsc.fc.core.service.assetstore.AssetStore;
import eu.xfsc.fc.core.service.dcp.DcpPresentationFacade;
import eu.xfsc.fc.core.service.graphdb.GraphStore;
import eu.xfsc.fc.core.service.verification.VerificationService;

class AdminInspectionServiceTest {
  private AssetStore assetStore;
  private GraphStore graphStore;
  private QueryLanguageValidator validator;
  private VerificationService verificationService;
  private DcpPresentationFacade dcpPresentationFacade;
  private AdminInspectionService service;

  @BeforeEach
  void admin() {
    assetStore = org.mockito.Mockito.mock(AssetStore.class);
    graphStore = org.mockito.Mockito.mock(GraphStore.class);
    validator = org.mockito.Mockito.mock(QueryLanguageValidator.class);
    verificationService = org.mockito.Mockito.mock(VerificationService.class);
    dcpPresentationFacade = org.mockito.Mockito.mock(DcpPresentationFacade.class);
    when(dcpPresentationFacade.tryParsePresentationResponse(any(), any())).thenReturn(Optional.empty());
    service = new AdminInspectionService(
        assetStore, graphStore, validator, verificationService, dcpPresentationFacade);
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
  void administratorStoresACredentialWithoutADcpIdentity() {
    CredentialVerificationResult verified = org.mockito.Mockito.mock(CredentialVerificationResult.class);
    when(verified.getId()).thenReturn("https://example.org/credential");
    when(verified.getIssuer()).thenReturn("did:example:issuer");
    when(verified.getValidators()).thenReturn(List.of());
    when(verified.getWarnings()).thenReturn(List.of("context was cached"));
    when(verificationService.verifyCredential(any(), eq(true), eq(true), eq(true), eq(false))).thenReturn(verified);

    var stored = service.postCredential("eyJhbGciOiJFZERTQSJ9.payload.sig", "application/vc+jwt;charset=UTF-8");

    assertEquals("https://example.org/credential", stored.asset().id());
    assertEquals("did:example:issuer", stored.asset().issuer());
    assertEquals("application/vc+jwt", stored.asset().contentType());
    assertEquals(List.of("context was cached"), stored.warnings());
    verify(assetStore).storeCredential(any(), eq(verified));
  }

  @Test
  void unsignedJsonCredentialSkipsSignatureVerification() {
    CredentialVerificationResult verified = org.mockito.Mockito.mock(CredentialVerificationResult.class);
    when(verified.getId()).thenReturn("https://example.org/test/1");
    when(verified.getIssuer()).thenReturn("did:example:catalogue-test");
    when(verified.getValidators()).thenReturn(List.of());
    when(verificationService.verifyCredential(any(), eq(true), eq(false), eq(false), eq(false))).thenReturn(verified);

    service.postCredential("{\"type\":[\"VerifiableCredential\"]}", "text/plain");

    verify(verificationService).verifyCredential(any(), eq(true), eq(false), eq(false), eq(false));
    verify(assetStore).storeCredential(any(), eq(verified));
  }

  @Test
  void credentialPostRejectsADcpPresentationAndANonCredential() {
    when(dcpPresentationFacade.tryParsePresentationResponse(any(), any()))
        .thenReturn(Optional.of(org.mockito.Mockito.mock(PresentationResponseMessage.class)));
    assertThrows(ClientException.class, () -> service.postCredential("{}", "application/json"));
    verify(verificationService, never()).verifyCredential(any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean());

    when(dcpPresentationFacade.tryParsePresentationResponse(any(), any())).thenReturn(Optional.empty());
    when(verificationService.verifyCredential(any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean()))
        .thenReturn(org.mockito.Mockito.mock(NonCredentialVerificationResult.class));
    assertThrows(ClientException.class, () -> service.postCredential("{}", "application/json"));
    verify(assetStore, never()).storeCredential(any(), any());
  }

  @Test
  void credentialPostRequiresAnApplicationAdministrator() {
    SecurityContextHolder.clearContext();
    assertThrows(AccessDeniedException.class, () -> service.postCredential("{}", "application/json"));
    verify(verificationService, never()).verifyCredential(any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean());
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
