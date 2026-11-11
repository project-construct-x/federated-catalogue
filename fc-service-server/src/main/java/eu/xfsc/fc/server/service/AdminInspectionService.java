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

import static eu.xfsc.fc.server.util.SessionUtils.requireApplicationAdmin;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import eu.xfsc.fc.api.generated.model.QueryInfo;
import eu.xfsc.fc.api.generated.model.QueryLanguage;
import eu.xfsc.fc.api.generated.model.Results;
import eu.xfsc.fc.core.exception.ClientException;
import eu.xfsc.fc.core.exception.NotFoundException;
import eu.xfsc.fc.core.pojo.AssetFilter;
import eu.xfsc.fc.core.pojo.AssetMetadata;
import eu.xfsc.fc.core.pojo.GraphQuery;
import eu.xfsc.fc.core.pojo.PaginatedResults;
import eu.xfsc.fc.core.service.assetstore.AssetRecord;
import eu.xfsc.fc.core.service.assetstore.AssetStore;
import eu.xfsc.fc.core.service.graphdb.GraphStore;
import lombok.RequiredArgsConstructor;

/**
 * Read-only catalogue inspection for application administrators.
 * Machine callers keep using the DCP asset and query APIs.
 */
@Service
@RequiredArgsConstructor
public class AdminInspectionService {
  private static final int MAX_PAGE = 200;
  private static final Pattern WRITE_QUERY = Pattern.compile(
      "(?i)\\b(INSERT|DELETE|LOAD|CLEAR|DROP|CREATE|MERGE|DETACH|SET|REMOVE)\\b");

  private final AssetStore assetStore;
  private final GraphStore graphStore;
  private final QueryLanguageValidator queryLanguageValidator;

  public PostedAssetPage postedAssets(int offset, int limit) {
    requireApplicationAdmin();
    int pageSize = Math.min(Math.max(limit, 1), MAX_PAGE);
    int start = Math.max(offset, 0);
    AssetFilter filter = new AssetFilter();
    filter.setOffset(start);
    filter.setLimit(pageSize);
    PaginatedResults<AssetMetadata> page = assetStore.getByFilter(filter, true, false);
    List<PostedAsset> items = page.getResults().stream().map(AdminInspectionService::toPostedAsset).toList();
    return new PostedAssetPage((int) page.getTotalCount(), items);
  }

  public PostedAssetContent postedContent(String id) {
    requireApplicationAdmin();
    if (StringUtils.isBlank(id)) {
      throw new ClientException("Asset id is required.");
    }
    AssetFilter filter = new AssetFilter();
    filter.setIds(List.of(id));
    filter.setOffset(0);
    filter.setLimit(1);
    PaginatedResults<AssetMetadata> page = assetStore.getByFilter(filter, true, true);
    if (page.getResults().isEmpty()) {
      throw new NotFoundException("No posted asset found for id " + id);
    }
    AssetMetadata asset = page.getResults().getFirst();
    String contentType = asset.getContentType();
    String content = null;
    boolean binary = true;
    if (textual(contentType) && asset instanceof AssetRecord record) {
      content = record.getContent();
      binary = false;
    }
    return new PostedAssetContent(asset.getId(), contentType, content, binary);
  }

  public QueryInfo queryInfo() {
    requireApplicationAdmin();
    QueryInfo info = new QueryInfo();
    info.setBackend(graphStore.getBackendType().name());
    graphStore.getSupportedQueryLanguage().ifPresentOrElse(language -> {
      QueryLanguageProperties props = QueryLanguageProperties.of(language);
      info.setEnabled(true);
      info.setQueryLanguage(QueryLanguage.fromValue(language.name()));
      info.setContentType(props.contentType());
      info.setExampleQuery(props.exampleQuery());
      info.setDocumentation(props.documentationUrl());
    }, () -> info.setEnabled(false));
    return info;
  }

  public Results query(String body, String contentType) {
    requireApplicationAdmin();
    if (StringUtils.isBlank(body)) {
      throw new ClientException("Query text is required.");
    }
    if (isWriteQuery(body)) {
      throw new ClientException("Only read queries are allowed here.");
    }
    QueryLanguage language;
    try {
      language = QueryLanguageProperties.fromContentType(contentType);
    } catch (IllegalArgumentException ex) {
      throw new ClientException(ex.getMessage());
    }
    queryLanguageValidator.validateLanguageSupport(language);
    String text = capReadQuery(body);
    PaginatedResults<java.util.Map<String, Object>> result =
        graphStore.queryData(new GraphQuery(text, null, language, GraphQuery.QUERY_TIMEOUT, true));
    return new Results((int) result.getTotalCount(), result.getResults());
  }

  static String capReadQuery(String query) {
    if (query.toUpperCase(Locale.ROOT).contains("LIMIT")) {
      return query;
    }
    String head = query
        .replaceAll("(?s)/\\*.*?\\*/", " ")
        .replaceAll("(?m)(#|//).*", " ")
        .replaceAll("(?i)\\b(?:PREFIX|BASE)\\s+\\S+\\s*<[^>]*>", " ")
        .trim();
    if (startsWithKeyword(head, "SELECT") || startsWithKeyword(head, "MATCH")) {
      return query.stripTrailing() + " LIMIT 100";
    }
    return query;
  }

  private static boolean startsWithKeyword(String text, String keyword) {
    return text.regionMatches(true, 0, keyword, 0, keyword.length())
        && (text.length() == keyword.length()
            || !Character.isLetterOrDigit(text.charAt(keyword.length())));
  }

  static boolean isWriteQuery(String query) {
    String stripped = query
        .replaceAll("(?s)<[^>]*>", " ")
        .replaceAll("(?s)\"(?:\\\\.|[^\"])*\"", " ")
        .replaceAll("(?s)'(?:\\\\.|[^'])*'", " ");
    return WRITE_QUERY.matcher(stripped).find();
  }

  private static boolean textual(String contentType) {
    if (contentType == null) {
      return true;
    }
    String type = contentType.toLowerCase();
    return type.startsWith("text/") || type.contains("json") || type.contains("xml")
        || type.contains("turtle") || type.contains("rdf") || type.contains("n-triples");
  }

  private static PostedAsset toPostedAsset(AssetMetadata asset) {
    return new PostedAsset(
        asset.getId(),
        asset.getAssetHash(),
        asset.getStatus() == null ? null : asset.getStatus().name(),
        asset.getIssuer(),
        asset.getContentType(),
        asset.getFileSize(),
        iso(asset.getUploadDatetime()),
        iso(asset.getStatusDatetime()));
  }

  private static String iso(Instant instant) {
    return instant == null ? null : instant.toString();
  }

  public record PostedAssetPage(int totalCount, List<PostedAsset> items) {}

  public record PostedAsset(
      String id,
      String assetHash,
      String status,
      String issuer,
      String contentType,
      Long fileSize,
      String uploadDatetime,
      String statusDatetime) {}

  public record PostedAssetContent(String id, String contentType, String content, boolean binary) {}
}
