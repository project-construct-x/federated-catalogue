package eu.xfsc.fc.server.config;

import eu.xfsc.fc.core.security.DcpAuthenticationToken;
import eu.xfsc.fc.core.service.dcp.DcpMachineAuthenticationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/** Isolated, opt-in cutover for POST /assets; other endpoints retain their existing policy. */
@Configuration
@ConditionalOnProperty(name = "federated-catalogue.dcp.asset-authentication-enabled", havingValue = "true")
public class DcpAssetSecurityConfig {

  /** Authenticates every upload with DCP, independently of browser sessions or Keycloak roles. */
  @Bean
  @Order(0)
  public SecurityFilterChain dcpAssetChain(HttpSecurity http, DcpMachineAuthenticationService service)
      throws Exception {
    return http.securityMatcher(new AntPathRequestMatcher("/assets", "POST"))
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(cache -> cache.disable())
        .addFilterBefore(new DcpAssetAuthenticationFilter(service), AnonymousAuthenticationFilter.class)
        .authorizeHttpRequests(auth -> auth.anyRequest().access((authentication, context) ->
            new AuthorizationDecision(authentication.get() instanceof DcpAuthenticationToken
                && authentication.get().isAuthenticated())))
        .build();
  }
}
