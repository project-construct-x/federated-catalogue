package eu.xfsc.fc.server.config;

import eu.xfsc.fc.core.security.DcpAuthenticationToken;
import eu.xfsc.fc.core.service.dcp.DcpMachineAuthenticationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Request-scoped DCP authentication. Registered only inside the asset-write security chain. */
final class DcpAssetAuthenticationFilter extends OncePerRequestFilter {
  private final DcpMachineAuthenticationService authenticationService;

  DcpAssetAuthenticationFilter(DcpMachineAuthenticationService authenticationService) {
    this.authenticationService = authenticationService;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    SecurityContextHolder.clearContext();
    try {
      DcpAuthenticationToken authentication;
      try {
        authentication = new DcpAuthenticationToken(
            authenticationService.authenticateAssetWrite(request.getHeader("Authorization")));
      } catch (AuthenticationServiceException ex) {
        response.sendError(503, "DCP authentication unavailable");
        return;
      } catch (AuthenticationException ex) {
        response.sendError(401, "DCP authentication required");
        return;
      }
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(authentication);
      SecurityContextHolder.setContext(context);
      chain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
