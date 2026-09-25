package eu.xfsc.fc.core.service.oid4vp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;

/** Gemeinsame Membership-Prüfung, genutzt von DCP und OID4VP. Aus DcpMachineAuthenticationService extrahieren. */
@Component
@RequiredArgsConstructor
public class MembershipPolicyEvaluator {
    private final VerificationService verification;

    public VerifiedMembership evaluate(JsonNode presentation, String expectedHolderDid,
                                       Collection<String> trustedIssuers) {
        // Genau 1 VC vom Typ MembershipCredential, Issuer vertrauenswürdig,
        // Subject == Holder, VC+VP-Signaturen prüfen, Status/BitstringStatusList prüfen
        // -> VerifiedMembership(holderDid, issuer, isConsumer, isProvider, presentationId)
    }
}

public record VerifiedMembership(String holderDid, String membershipIssuer,
                                 boolean consumer, boolean provider, String presentationId) {}
