package eu.xfsc.fc.server.config;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.xfsc.fc.core.pojo.AssetMetadata;
import eu.xfsc.fc.core.pojo.CredentialVerificationResult;
import eu.xfsc.fc.core.security.DcpIdentity;
import eu.xfsc.fc.core.service.assetstore.AssetStore;
import eu.xfsc.fc.core.service.assetstore.IriGenerator;
import eu.xfsc.fc.core.service.assetstore.RdfDetector;
import eu.xfsc.fc.core.service.dcp.DcpMachineAuthenticationService;
import eu.xfsc.fc.core.service.dcp.DcpPresentationFacade;
import eu.xfsc.fc.core.service.graphdb.GraphStore;
import eu.xfsc.fc.core.service.verification.ProtectedNamespaceFilter;
import eu.xfsc.fc.core.service.verification.VerificationService;
import eu.xfsc.fc.server.service.AssetUploadService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Real security chains and upload service, with external verification/storage isolated. */
@SpringJUnitWebConfig(DcpAssetSecurityTest.Config.class)
@TestPropertySource(properties = {"keycloak.resource=federated-catalogue",
    "federated-catalogue.dcp.asset-authentication-enabled=true"})
class DcpAssetSecurityTest {
  private static final String PARTICIPANT = "did:web:participant.example";
  @Autowired private WebApplicationContext context;
  @Autowired private DcpMachineAuthenticationService authentication;
  @Autowired private AssetStore store;
  @Autowired private VerificationService verification;
  @Autowired private RdfDetector detector;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    reset(authentication, store, verification, detector);
    when(authentication.authenticateAssetWrite(any())).thenThrow(new BadCredentialsException("private-token"));
    doReturn(new DcpIdentity(PARTICIPANT, PARTICIPANT))
        .when(authentication).authenticateAssetWrite("Bearer dcp");
    when(store.storeUnverified(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void dcpUploadReachesStoreWithoutKeycloakAndClearsContext() throws Exception {
    mvc.perform(post("/assets").header("Authorization", "Bearer dcp")
        .contentType("application/octet-stream").content("asset"))
        .andExpect(status().isCreated());
    verify(store).storeUnverified(argThat(asset -> PARTICIPANT.equals(asset.getIssuer())), isNull());
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  void keycloakAdminAndMissingDcpCannotUpload() throws Exception {
    mvc.perform(post("/assets").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN_ALL")))
        .header("Authorization", "Bearer keycloak").content("asset"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/assets").content("asset")).andExpect(status().isUnauthorized());
    verifyNoInteractions(store);
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  void adminEndpointsStillUseKeycloakRoles() throws Exception {
    mvc.perform(get("/admin/me").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN_ALL"))))
        .andExpect(status().isOk());
    mvc.perform(get("/admin/me").with(jwt())).andExpect(status().isForbidden());
    verifyNoInteractions(authentication);
  }

  @Test
  void participantUserManagementUsesAdminChain() throws Exception {
    mvc.perform(get("/participants/example/users").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN_ALL"))))
        .andExpect(status().isOk());
    mvc.perform(get("/participants/example/users").with(jwt())).andExpect(status().isForbidden());
    mvc.perform(get("/participants/example/users")).andExpect(status().isUnauthorized());
    mvc.perform(get("/participants/example/users").with(
        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
            new eu.xfsc.fc.core.security.DcpAuthenticationToken(new DcpIdentity(PARTICIPANT, null)))))
        .andExpect(status().isForbidden());
  }

  @Test
  void selfIssuedCredentialCannotOverwriteAnotherParticipantsAsset() throws Exception {
    when(detector.isRdf(any(), any())).thenReturn(true);
    when(verification.verifyCredential(any(), eq(true), eq(true), eq(true), eq(false)))
        .thenReturn(result(PARTICIPANT));
    when(store.existsById("urn:asset:1")).thenReturn(true);
    AssetMetadata existing = new AssetMetadata();
    existing.setIssuer("did:web:other.example");
    when(store.getById("urn:asset:1")).thenReturn(existing);
    mvc.perform(post("/assets").header("Authorization", "Bearer dcp")
        .contentType("application/vc+jwt").content("eyJ.asset.signature"))
        .andExpect(status().isForbidden());
    verify(store, never()).storeCredential(any(), any());
  }

  @Test
  void infrastructurePerimeterKeepsOnlyHealthAndDocsPublic() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mvc.perform(get("/api/docs")).andExpect(status().isOk());
    mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/probe")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/probe").with(jwt())).andExpect(status().isForbidden());
    mvc.perform(get("/verification")).andExpect(status().isUnauthorized());
    mvc.perform(options("/api/docs")).andExpect(status().isOk());
  }

  @Test
  void strictCredentialUploadChecksOwnershipBeforeStorage() throws Exception {
    when(detector.isRdf(any(), any())).thenReturn(true);
    when(verification.verifyCredential(any(), eq(true), eq(true), eq(true), eq(false)))
        .thenReturn(result("did:web:someone-else"));
    mvc.perform(post("/assets").header("Authorization", "Bearer dcp")
        .contentType("application/vc+jwt").content("eyJ.asset.signature"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(store);
    when(verification.verifyCredential(any(), eq(true), eq(true), eq(true), eq(false)))
        .thenReturn(result(PARTICIPANT));
    mvc.perform(post("/assets").header("Authorization", "Bearer dcp")
        .contentType("application/vc+jwt").content("eyJ.asset.signature"))
        .andExpect(status().isCreated());
    verify(store).storeCredential(any(AssetMetadata.class), any());
  }

  private CredentialVerificationResult result(String issuer) {
    return new CredentialVerificationResult(Instant.now(), "ACTIVE", issuer, Instant.now(),
        "urn:asset:1", List.of(), List.of(), null, null);
  }

  @Configuration
  @EnableWebMvc
  @Import({DcpAssetSecurityConfig.class, SecurityConfig.class})
  static class Config {
    @Bean DcpMachineAuthenticationService authentication() { return mock(DcpMachineAuthenticationService.class); }
    @Bean AssetStore store() { return mock(AssetStore.class); }
    @Bean VerificationService verification() { return mock(VerificationService.class); }
    @Bean RdfDetector detector() { return mock(RdfDetector.class); }
    @Bean JwtDecoder jwtDecoder() { return mock(JwtDecoder.class); }
    @Bean AssetUploadService uploads() {
      return new AssetUploadService(verification(), store(), detector(), mock(IriGenerator.class),
          mock(ProtectedNamespaceFilter.class), mock(GraphStore.class), new ObjectMapper(),
          mock(DcpPresentationFacade.class));
    }
    @Bean UploadController controller() { return new UploadController(uploads()); }
  }

  @RestController
  static class UploadController {
    private final AssetUploadService uploads;
    UploadController(AssetUploadService uploads) { this.uploads = uploads; }
    @PostMapping("/assets")
    ResponseEntity<Void> upload(@RequestBody byte[] body, @RequestHeader("Content-Type") String type) {
      uploads.processUpload(body, type, null);
      return ResponseEntity.status(201).build();
    }
    @GetMapping("/admin/me") String admin() { return "admin"; }
    @GetMapping("/participants/{id}/users") String participantUsers() { return "users"; }
    @GetMapping({"/actuator/health", "/api/docs"}) String publicResource() { return "public"; }
  }
}
