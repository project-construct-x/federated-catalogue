package eu.xfsc.fc.core.config;

import de.eecc.oid4vc.oid4vp.api.Oid4Vp;
import de.eecc.oid4vc.oid4vp.api.Oid4VpOptions;
import eu.xfsc.fc.core.service.oid4vp.Oid4vpBootstrapProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Oid4VpConfig {

    @Bean
    public Oid4Vp oid4Vp(Oid4vpBootstrapProperties props) {
        // WICHTIG: hier die EECC-API-Fabrikmethode oder den Konstruktor einsetzen,
        // die in der Bibliothek tatsächlich existiert.
        // Beispiel-Form:
        // return new Oid4Vp(props.getVerifierUrl(), props.getClientId(), props.getResponseUri());
        // oder:
        // return Oid4VpFactory.createDefault(props.getVerifierUrl(), props.getClientId());
        Oid4Vp oid4Vp = Oid4Vp.create(Oid4VpOptions.builder()
                .verifierUrl(/*"http://vc-verifier:3000/api/verifier"*/props.getVerifierUrl()) // recommended: EECC VC Verifier (see link above)
                .responseUri("https://example.com/api/auth/oid4vp/response")
                .redirectUri("https://example.com/login/oid4vp")
                .requestUriBaseUrl("https://example.com/api/auth/oid4vp")
                .requestUriEnabled(true)
                .build());

//        throw new UnsupportedOperationException(
//                "Use the actual EECC Oid4Vp factory/constructor from the library API here");
        return oid4Vp;
    }
}
