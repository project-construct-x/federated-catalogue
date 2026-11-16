package eu.xfsc.fc.server.controller;

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

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import eu.xfsc.fc.api.generated.model.QueryInfo;
import eu.xfsc.fc.api.generated.model.Results;
import eu.xfsc.fc.server.service.AdminInspectionService;
import eu.xfsc.fc.server.service.AdminInspectionService.PostedAssetContent;
import eu.xfsc.fc.server.service.AdminInspectionService.PostedAssetPage;
import eu.xfsc.fc.server.service.AdminInspectionService.PostedCredentialResult;
import lombok.RequiredArgsConstructor;

/** Administrator inspection, read-only graph query, and direct credential storage. */
@RestController
@RequiredArgsConstructor
public class AdminInspectionController {
  private final AdminInspectionService service;

  @PostMapping(value = "/admin/posted-assets", consumes = {
      "application/json",
      "application/ld+json",
      "application/vc+ld+json",
      "application/vp+ld+json",
      "application/vc+jwt",
      "application/vp+jwt",
      "text/plain"})
  public ResponseEntity<PostedCredentialResult> postCredential(
      @RequestBody String body,
      @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
    PostedCredentialResult stored = service.postCredential(body, contentType);
    String id = UriUtils.encodeQueryParam(stored.asset().id(), StandardCharsets.UTF_8);
    return ResponseEntity.created(URI.create("/admin/posted-assets/content?id=" + id)).body(stored);
  }

  @GetMapping("/admin/posted-assets")
  public PostedAssetPage postedAssets(
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "100") int limit) {
    return service.postedAssets(offset, limit);
  }

  @GetMapping("/admin/posted-assets/content")
  public PostedAssetContent postedContent(@RequestParam String id) {
    return service.postedContent(id);
  }

  @GetMapping("/admin/query/info")
  public QueryInfo queryInfo() {
    return service.queryInfo();
  }

  @PostMapping(value = "/admin/query", consumes = {
      "application/sparql-query", "application/opencypher-query", "text/plain"})
  public Results query(
      @RequestBody String body,
      @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType) {
    return service.query(body, contentType);
  }
}
