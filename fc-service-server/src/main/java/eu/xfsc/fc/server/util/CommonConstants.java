package eu.xfsc.fc.server.util;

/**
 * Roles and permissions constant class.
 */
public final class CommonConstants {
  private CommonConstants() {}

  public static final String PREFIX = "ROLE_";
  public static final String ADMIN_ALL = "ADMIN_ALL";

  // Prefixed permission roles (Spring Security ROLE_ prefix)
  public static final String ADMIN_ALL_WITH_PREFIX = PREFIX + ADMIN_ALL;
}
