package eu.xfsc.fc.core.service.oid4vp;

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

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.eecc.oid4vc.oid4vp.api.Oid4Vp;
import de.eecc.oid4vc.oid4vp.request.PresentationRequest;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import eu.xfsc.fc.core.dao.oid4vp.BootstrapTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BootstrapTokenService {
    public static final String TOKEN_TYPE = "bootstrap+jwt";
    public static final String SCOPE_BIND = "connector:bind";
    private final Oid4Vp oid4Vp;
    private final BootstrapTokenRepository tokens;
    private final ConnectorBindingChallengeService challenges;
    private final Oid4vpBootstrapProperties props;

    @Transactional
    public BootstrapTokenResponse redeem(String state, String responseCode) {
        PresentationRequest request = oid4Vp.findPresentationRequestByResponseCode(responseCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown OID4VP response_code"));
        if (!Objects.equals(state, request.getState())) {
            throw new IllegalArgumentException("OID4VP state does not match response_code");
        }

        VerifiedMembership m = extractMembership(request);
        Challenge challenge = resolveChallenge(request);
        String connectorDid = challenge.connectorDid();
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(props.getTokenTtl());
        String jwt = sign(new JWTClaimsSet.Builder()
                .issuer(props.getTokenIssuer()).subject(m.holderDid()).audience(props.getTokenIssuer())
                .jwtID(jti).issueTime(Date.from(now)).expirationTime(Date.from(expiresAt))
                .claim("scope", SCOPE_BIND).claim("connector_did", connectorDid)
                .claim("challenge_id", challenge.id())
                .claim("membership_issuer", m.membershipIssuer())
                .claim("is_consumer", m.consumer()).claim("is_provider", m.provider())
                .claim("presentation_id", m.presentationId()).build());   // Header typ=bootstrap+jwt

        tokens.save(new BootstrapTokenRepository.BootstrapTokenRecord(jti, m.holderDid(), connectorDid,
                m.membershipIssuer(), m.presentationId(), SCOPE_BIND, TOKEN_TYPE, now, expiresAt, null, null));
        oid4Vp.invalidateResponseCode(request);
        return new BootstrapTokenResponse(jwt, "Bearer", props.getTokenTtl().toSeconds());
    }

    public BootstrapPrincipal verify(String jwt) {
        JWTClaimsSet claims = verifyAndReadClaims(jwt);
        String jti = claims.getJWTID();
        BootstrapTokenRepository.BootstrapTokenRecord stored = tokens.findByJti(jti)
                .orElseThrow(() -> new IllegalArgumentException("Unknown bootstrap token"));
        if (stored.revokedAt() != null || stored.redeemedAt() != null || !Instant.now().isBefore(stored.expiresAt())) {
            throw new IllegalArgumentException("Bootstrap token is no longer active");
        }
        try {
            return principalFromClaims(claims);
        } catch (ParseException e) {
            throw new IllegalArgumentException("Invalid bootstrap token claims", e);
        }
    }

    /** Einmalig verbrauchen: UPDATE … SET redeemed_at = now() WHERE jti = ? AND redeemed_at IS NULL AND NOT revoked. */
    @Transactional
    public BootstrapPrincipal consume(String jwt, String connectorDid) {
        BootstrapPrincipal principal = verify(jwt);
        if (!Objects.equals(connectorDid, principal.connectorDid())) {
            throw new IllegalArgumentException("Bootstrap token connector mismatch");
        }
        if (!tokens.markRedeemedIfActive(principal.jti(), Instant.now())) {
            throw new IllegalArgumentException("Bootstrap token was already used, revoked or expired");
        }
        return principal;
    }

    private String sign(JWTClaimsSet claims) {
        try {
            JWK key = signingKey();
            JWSAlgorithm algorithm = algorithmFor(key);
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm).type(new JOSEObjectType(TOKEN_TYPE)).build(),
                    claims);
            if (key instanceof RSAKey rsaKey) {
                jwt.sign(new RSASSASigner(rsaKey));
            } else if (key instanceof ECKey ecKey) {
                jwt.sign(new ECDSASigner(ecKey));
            } else if (key instanceof OctetSequenceKey octetKey) {
                jwt.sign(new MACSigner(octetKey));
            } else {
                throw new IllegalStateException("Unsupported bootstrap token signing key type: " + key.getKeyType());
            }
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Unable to sign bootstrap token", e);
        }
    }

    private JWTClaimsSet verifyAndReadClaims(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!TOKEN_TYPE.equals(jwt.getHeader().getType() == null ? null : jwt.getHeader().getType().toString())) {
                throw new IllegalArgumentException("Not a bootstrap token");
            }
            JWSVerifier verifier = verifierFor(signingKey());
            if (!jwt.verify(verifier)) {
                throw new IllegalArgumentException("Invalid bootstrap token signature");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date expiration = claims.getExpirationTime();
            if (expiration == null || !expiration.toInstant().isAfter(Instant.now())) {
                throw new IllegalArgumentException("Bootstrap token expired");
            }
            if (!SCOPE_BIND.equals(claims.getStringClaim("scope"))) {
                throw new IllegalArgumentException("Bootstrap token scope is not allowed");
            }
            return claims;
        } catch (ParseException | JOSEException e) {
            throw new IllegalArgumentException("Invalid bootstrap token", e);
        }
    }

    private JWK signingKey() {
        if (props.getTokenSigningKey() == null || props.getTokenSigningKey().isBlank()) {
            throw new IllegalStateException("federated-catalogue.oid4vp.token-signing-key is required");
        }
        try {
            return JWK.parse(props.getTokenSigningKey());
        } catch (ParseException e) {
            throw new IllegalStateException("Invalid bootstrap token JWK", e);
        }
    }

    private static JWSAlgorithm algorithmFor(JWK key) {
        if (key.getAlgorithm() instanceof JWSAlgorithm algorithm) {
            return algorithm;
        }
        if (key instanceof RSAKey) {
            return JWSAlgorithm.RS256;
        }
        if (key instanceof ECKey ecKey) {
            Curve curve = ecKey.getCurve();
            if (Curve.P_384.equals(curve)) {
                return JWSAlgorithm.ES384;
            }
            if (Curve.P_521.equals(curve)) {
                return JWSAlgorithm.ES512;
            }
            return JWSAlgorithm.ES256;
        }
        if (key instanceof OctetSequenceKey) {
            return JWSAlgorithm.HS256;
        }
        throw new IllegalStateException("Unsupported bootstrap token key type: " + key.getKeyType());
    }

    private static JWSVerifier verifierFor(JWK key) throws JOSEException {
        if (key instanceof RSAKey rsaKey) {
            return new RSASSAVerifier(rsaKey.toRSAPublicKey());
        }
        if (key instanceof ECKey ecKey) {
            return new ECDSAVerifier(ecKey.toECPublicKey());
        }
        if (key instanceof OctetSequenceKey octetKey) {
            return new MACVerifier(octetKey);
        }
        throw new IllegalStateException("Unsupported bootstrap token key type: " + key.getKeyType());
    }

    private VerifiedMembership extractMembership(PresentationRequest request) {
        String holderDid = request.getClientId();
        String membershipIssuer = request.getClientId();
        String presentationId = request.getRequestId() != null ? request.getRequestId() : request.getState();
        if (request.getVpToken() != null && !request.getVpToken().isBlank()) {
            try {
                JWTClaimsSet vpClaims = SignedJWT.parse(request.getVpToken()).getJWTClaimsSet();
                holderDid = valueOrDefault(vpClaims.getSubject(), holderDid);
                membershipIssuer = valueOrDefault(extractMembershipIssuer(vpClaims), membershipIssuer);
                presentationId = valueOrDefault(vpClaims.getJWTID(), presentationId);
            } catch (ParseException ignored) {
                // Keep request-derived fallback values; VP validation already happened in the OID4VP library.
            }
        }
        return new VerifiedMembership(holderDid, membershipIssuer, false, false, presentationId);
    }

    private static String extractMembershipIssuer(JWTClaimsSet vpClaims) throws ParseException {
        Object vp = vpClaims.getClaim("vp");
        if (vp instanceof Map<?, ?> vpMap) {
            Object credentials = vpMap.get("verifiableCredential");
            if (credentials instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof String vcJwt) {
                return SignedJWT.parse(vcJwt).getJWTClaimsSet().getIssuer();
            }
        }
        return vpClaims.getIssuer();
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Challenge resolveChallenge(PresentationRequest request) {
        return challenges.findActiveByState(request.getState())
                .orElseThrow(() -> new IllegalStateException("No connector binding challenge found for OID4VP state"));
    }

    private static BootstrapPrincipal principalFromClaims(JWTClaimsSet claims) throws ParseException {
        boolean consumer = Boolean.TRUE.equals(claims.getBooleanClaim("is_consumer"));
        boolean provider = Boolean.TRUE.equals(claims.getBooleanClaim("is_provider"));
        return new BootstrapPrincipal(claims.getJWTID(), claims.getSubject(), claims.getStringClaim("connector_did"),
                claims.getStringClaim("challenge_id"), claims.getStringClaim("membership_issuer"), consumer, provider,
                claims.getStringClaim("presentation_id"));
    }
}
