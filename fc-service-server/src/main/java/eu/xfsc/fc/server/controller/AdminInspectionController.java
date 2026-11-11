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

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import eu.xfsc.fc.api.generated.model.QueryInfo;
import eu.xfsc.fc.api.generated.model.Results;
import eu.xfsc.fc.server.service.AdminInspectionService;
import eu.xfsc.fc.server.service.AdminInspectionService.PostedAssetContent;
import eu.xfsc.fc.server.service.AdminInspectionService.PostedAssetPage;
import lombok.RequiredArgsConstructor;

/** Administrator reads of stored assets and the graph. Writes stay on the DCP APIs. */
@RestController
@RequiredArgsConstructor
public class AdminInspectionController {
  private final AdminInspectionService service;

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
