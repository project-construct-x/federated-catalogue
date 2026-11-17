package eu.xfsc.fc.core.service.dcp;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator;
import com.nimbusds.jwt.SignedJWT;
import eu.xfsc.fc.core.config.DcpProperties;
import eu.xfsc.fc.core.exception.ServerException;
import java.util.List;
import org.junit.jupiter.api.Test;

class VerifierSiTokenFactoryTest {

  @Test
  void signedTokenTargetsHolderDidAndPreservesOpaqueGrant() throws Exception {
    var key = new OctetKeyPairGenerator(Curve.Ed25519).keyID("#key-1").generate();
    var properties = new DcpProperties();
    properties.setVerifierDid("did:web:catalogue.example");
    properties.setVerifierSigningKey(key.toJSONString());
    var factory = new VerifierSiTokenFactory(properties);

    var token = SignedJWT.parse(factory.create("did:web:holder.example", "opaque-grant"));
    var claims = token.getJWTClaimsSet();
    assertTrue(token.verify(new Ed25519Verifier(key.toPublicJWK())));
    assertEquals(List.of("did:web:holder.example"), claims.getAudience());
    assertEquals(properties.getVerifierDid(), claims.getIssuer());
    assertEquals(claims.getIssuer(), claims.getSubject());
    assertEquals("opaque-grant", claims.getStringClaim("token"));
    assertEquals("did:web:catalogue.example#key-1", token.getHeader().getKeyID());
    assertTrue(claims.getExpirationTime().after(claims.getIssueTime()));
    assertNotEquals(claims.getJWTID(), SignedJWT.parse(factory.create("did:web:holder.example", "opaque-grant"))
        .getJWTClaimsSet().getJWTID());
    assertThrows(ServerException.class, () -> factory.create("https://holder.example/credentials", "opaque-grant"));
    assertThrows(ServerException.class, () -> factory.create(null, "opaque-grant"));
  }
}
