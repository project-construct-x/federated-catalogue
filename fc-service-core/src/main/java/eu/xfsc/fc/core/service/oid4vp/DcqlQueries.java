package eu.xfsc.fc.core.service.oid4vp;

import java.util.List;
import java.util.Map;

public final class DcqlQueries {
    private DcqlQueries() {
    }

    public static Map<String, Object> membership(List<String> trustedIssuers, boolean requireCredentialStatus) {
        return Map.of(
                "credentials", List.of(
                        Map.of(
                                "id", "membership_credential",
                                "format", "dc+json",
                                "meta", Map.of(
                                        "type", "MembershipCredential"
                                ),
                                "constraints", Map.of(
                                        "fields", List.of(
                                                Map.of(
                                                        "path", "[]",
                                                        "filter", Map.of(
                                                                "type", "string",
                                                                "pattern", ".*"
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );
    }
}
