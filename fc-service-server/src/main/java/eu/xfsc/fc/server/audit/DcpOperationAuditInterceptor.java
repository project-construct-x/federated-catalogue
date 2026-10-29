package eu.xfsc.fc.server.audit;

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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import eu.xfsc.fc.core.security.DcpAuthenticationToken;
import eu.xfsc.fc.core.security.DcpIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/** HTTP operation audit; complements Envers mutation history without reading authentication payloads. */
public final class DcpOperationAuditInterceptor implements HandlerInterceptor {
  private static final Logger AUDIT = LoggerFactory.getLogger("eu.xfsc.fc.server.audit.DcpOperationAudit");
  private static final ObjectWriter JSON = new ObjectMapper().writer();
  private static final String STATE = DcpOperationAuditInterceptor.class.getName() + ".state";

  private record RequestAudit(String operation, String resource, DcpIdentity identity) { }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    // Retain the original trusted context across an asynchronous MVC redispatch.
    if (request.getAttribute(STATE) == null && !"OPTIONS".equals(request.getMethod())) {
      var authentication = SecurityContextHolder.getContext().getAuthentication();
      DcpIdentity identity = authentication instanceof DcpAuthenticationToken dcp && dcp.isAuthenticated()
          ? dcp.getPrincipal() : null;
      String route = String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
      request.setAttribute(STATE, new RequestAudit(request.getMethod() + " " + route,
          withoutPathParameters(request.getRequestURI()), identity));
    }
    return true;
  }

  @Override
  public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
      Exception exception) {
    RequestAudit state = (RequestAudit) request.getAttribute(STATE);
    request.removeAttribute(STATE);
    if (state == null) {
      return;
    }
    // An escaping exception is translated by the outer security filter/container after MVC completes.
    // Do not record its still-default response status as a successful HTTP response.
    Integer status = exception == null ? response.getStatus() : null;
    String outcome = exception != null ? (isAccessFailure(exception) ? "DENIED" : "FAILED")
        : status == 401 || status == 403 ? "DENIED"
        : status >= 200 && status < 300 ? "SUCCESS" : "FAILED";
    // Never label a successful non-DCP call as an authenticated machine operation.
    if (state.identity() == null && !"DENIED".equals(outcome)) {
      return;
    }
    String resource = state.resource();
    if ("SUCCESS".equals(outcome) && status == 201) {
      String location = response.getHeader("Location");
      if (location != null) {
        try {
          URI uri = URI.create(location);
          if (!uri.isAbsolute() && uri.getRawAuthority() == null && uri.getRawPath() != null
              && (uri.getRawPath().startsWith("/assets/") || uri.getRawPath().startsWith("/participants/"))) {
            resource = withoutPathParameters(uri.getRawPath());
          }
        } catch (IllegalArgumentException ignored) {
          // An invalid Location must not cause a successful operation to fail; keep the request resource.
        }
      }
    }
    DcpIdentity identity = state.identity();
    var event = new OperationAudit("dcp_operation", Instant.now().toString(), state.operation(), resource,
        outcome, status, identity == null ? null : identity.participantDid(),
        identity == null ? null : identity.actorDid(), identity == null ? null : "DCP");
    try {
      // JSON escaping keeps caller-controlled identifiers on one line. Never serialize a request or exception.
      AUDIT.info("{}", JSON.writeValueAsString(event));
    } catch (JsonProcessingException ex) {
      AUDIT.error("Could not serialize DCP operation audit event");
    }
  }

  private static String withoutPathParameters(String path) {
    return path.replaceAll(";[^/]*", "");
  }

  private static boolean isAccessFailure(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof AccessDeniedException || cause instanceof AuthenticationException) {
        return true;
      }
    }
    return false;
  }

  private record OperationAudit(String event, String timestamp, String operation, String resource,
      String outcome, Integer httpStatus, String participantDid, String actorDid, String authenticationMethod) { }
}
