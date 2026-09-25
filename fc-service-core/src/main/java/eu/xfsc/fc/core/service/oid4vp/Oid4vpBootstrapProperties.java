package eu.xfsc.fc.core.service.oid4vp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "federated-catalogue.oid4vp")
public class Oid4vpBootstrapProperties {
    private boolean enabled = false;
    private String verifierUrl;              // EECC VC Verifier
    private String clientId;                 // Katalog als Verifier
    private String responseUri;              // .../api/auth/oid4vp/direct-post
    private List<String> trustedMembershipIssuers = List.of();
    private boolean requireCredentialStatus = true;
    private String tokenIssuer;              // z. B. https://catalogue.example/oid4vp
    private String tokenSigningKey;          // private JWK (nicht Keycloak)
    private Duration requestTtl = Duration.ofMinutes(5);
    private Duration tokenTtl = Duration.ofMinutes(5);
    private Duration challengeTtl = Duration.ofMinutes(10);
}
