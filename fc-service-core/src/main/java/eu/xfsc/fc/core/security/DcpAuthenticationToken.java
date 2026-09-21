package eu.xfsc.fc.core.security;

import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** Authentication created only by the DCP boundary after all verification checks succeed. */
public final class DcpAuthenticationToken extends AbstractAuthenticationToken {
  private final DcpIdentity identity;

  /** Takes verified identity data only; no bearer tokens or presentations are retained. */
  public DcpAuthenticationToken(DcpIdentity identity) {
    super(List.of());
    this.identity = Objects.requireNonNull(identity);
    super.setAuthenticated(true);
  }

  @Override
  public DcpIdentity getPrincipal() {
    return identity;
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public String getName() {
    return identity.participantDid();
  }

  @Override
  public void setAuthenticated(boolean authenticated) {
    if (authenticated) {
      throw new IllegalArgumentException("Create a new token after DCP verification");
    }
    super.setAuthenticated(false);
  }
}
