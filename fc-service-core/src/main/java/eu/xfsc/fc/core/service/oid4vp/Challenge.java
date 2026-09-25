package eu.xfsc.fc.core.service.oid4vp;

import java.time.Instant;
import java.util.UUID;

public record Challenge(
        String id,
        String connectorDid,
        String nonce,
        String state,
        Instant createdAt,
        Instant expiresAt
) {
    public boolean isExpiredAt(Instant now) {
        return now.isAfter(expiresAt);
    }
}
