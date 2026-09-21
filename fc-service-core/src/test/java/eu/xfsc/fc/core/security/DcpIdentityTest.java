package eu.xfsc.fc.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DcpIdentityTest {

  private static final String PARTICIPANT = "did:web:participant.example";
  private static final String ACTOR = "did:web:connector.example";

  @Test
  void preservesSeparateParticipantAndActorIdentities() {
    DcpIdentity identity = new DcpIdentity(PARTICIPANT, ACTOR);

    assertEquals(PARTICIPANT, identity.participantDid());
    assertEquals(ACTOR, identity.actorDid());
    assertEquals(DcpIdentity.AuthenticationMethod.DCP, identity.authenticationMethod());
  }

  @Test
  void absentActorIsNotInferredFromParticipant() {
    DcpIdentity identity = new DcpIdentity(PARTICIPANT, null);

    assertEquals(PARTICIPANT, identity.participantDid());
    assertNull(identity.actorDid());
    assertEquals(DcpIdentity.AuthenticationMethod.DCP, identity.authenticationMethod());
  }

  @Test
  void actorMayBeTheParticipant() {
    DcpIdentity identity = new DcpIdentity(PARTICIPANT, PARTICIPANT);

    assertEquals(PARTICIPANT, identity.actorDid());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t\n"})
  void rejectsMissingParticipantEvenWhenActorIsPresent(String participantDid) {
    assertThrows(IllegalArgumentException.class, () -> new DcpIdentity(participantDid, ACTOR));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t\n"})
  void rejectsBlankActor(String actorDid) {
    assertThrows(IllegalArgumentException.class, () -> new DcpIdentity(PARTICIPANT, actorDid));
  }
}
