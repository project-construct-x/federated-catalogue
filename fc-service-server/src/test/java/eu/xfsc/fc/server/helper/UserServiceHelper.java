package eu.xfsc.fc.server.helper;

import static eu.xfsc.fc.server.util.CommonConstants.ADMIN_ALL;

import java.util.ArrayList;
import java.util.List;
import org.keycloak.representations.idm.RoleRepresentation;

public class UserServiceHelper {
  public static List<RoleRepresentation> getAllRoles() {
    List<RoleRepresentation> roles = new ArrayList<>();
    roles.add(new RoleRepresentation(ADMIN_ALL, ADMIN_ALL, false));
    return roles;
  }
}
