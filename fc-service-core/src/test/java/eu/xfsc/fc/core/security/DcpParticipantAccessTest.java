package eu.xfsc.fc.core.security;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;

class DcpParticipantAccessTest {
  private final DcpIdentity identity = new DcpIdentity("did:web:participant", "did:web:actor");

  @Test
  void ownParticipantAllowedButActorAndOtherParticipantsDenied() {
    var authentication = new DcpAuthenticationToken(identity);
    assertDoesNotThrow(() -> DcpParticipantAccess.checkAccess(authentication, identity.participantDid()));
    assertThrows(AccessDeniedException.class,
        () -> DcpParticipantAccess.checkAccess(authentication, identity.actorDid()));
    assertThrows(AccessDeniedException.class,
        () -> DcpParticipantAccess.checkAccess(authentication, "did:web:other"));
    assertThrows(AccessDeniedException.class, () -> DcpParticipantAccess.checkAccess(authentication, null));
  }

  @Test
  void missingOrRevokedAuthenticationDenied() {
    assertThrows(AccessDeniedException.class, () -> DcpParticipantAccess.checkAccess(null, identity.participantDid()));
    var authentication = new DcpAuthenticationToken(identity);
    authentication.setAuthenticated(false);
    assertThrows(AccessDeniedException.class,
        () -> DcpParticipantAccess.checkAccess(authentication, identity.participantDid()));
    assertThrows(IllegalArgumentException.class, () -> authentication.setAuthenticated(true));
  }

  @Test
  void adminRolesCannotMakeAnUnverifiedIdentityTrusted() {
    var authentication = new TestingAuthenticationToken(identity, null, "ROLE_ADMIN_ALL", "ROLE_Ro-MU-CA");
    assertThrows(AccessDeniedException.class,
        () -> DcpParticipantAccess.checkAccess(authentication, identity.participantDid()));
  }

  @Test
  void authenticationRetainsNeitherCredentialsNorRoles() {
    var authentication = new DcpAuthenticationToken(identity);
    assertNull(authentication.getCredentials());
    assertTrue(authentication.getAuthorities().isEmpty());
    assertEquals(identity.participantDid(), authentication.getName());
  }
}
