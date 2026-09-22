package eu.xfsc.fc.server.util;

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

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import eu.xfsc.fc.server.config.CustomJwtAuthenticationConverter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

class AuthenticationLoggingTest {

  @Test
  void authenticationAndSessionLookup_doNotLogTokenOrPresentationContents() {
    Logger converterLogger = (Logger) LoggerFactory.getLogger(CustomJwtAuthenticationConverter.class);
    Logger sessionLogger = (Logger) LoggerFactory.getLogger(SessionUtils.class);
    Level converterLevel = converterLogger.getLevel();
    Level sessionLevel = sessionLogger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    converterLogger.addAppender(appender);
    sessionLogger.addAppender(appender);
    converterLogger.setLevel(Level.TRACE);
    sessionLogger.setLevel(Level.TRACE);

    String token = "sensitive-token-value";
    String presentation = "sensitive-presentation-contents";
    // A principal's string representation is not a safe logging contract.
    Jwt jwt = new Jwt(token, Instant.now(), Instant.now().plusSeconds(60),
        Map.of("alg", "RS256"), Map.of("sub", "actor", "participant_id", "participant",
            "vp", presentation, "resource_access",
            Map.of("federated-catalogue", Map.of("roles", List.of("ASSET_READ"))))) {
      @Override
      public String toString() {
        return getTokenValue() + " " + getClaims();
      }
    };

    try {
      var authentication = new CustomJwtAuthenticationConverter("federated-catalogue").convert(jwt);
      SecurityContextHolder.getContext().setAuthentication(authentication);

      assertThat(SessionUtils.getSessionParticipantId()).isEqualTo("participant");
      assertThat(SessionUtils.getSessionUserId()).isEqualTo("actor");
      assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
          .contains("ROLE_ASSET_READ");
      assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
          .allSatisfy(message -> assertThat(message).doesNotContain(token, presentation));
    } finally {
      SecurityContextHolder.clearContext();
      converterLogger.detachAppender(appender);
      sessionLogger.detachAppender(appender);
      converterLogger.setLevel(converterLevel);
      sessionLogger.setLevel(sessionLevel);
      appender.stop();
    }
  }
}
