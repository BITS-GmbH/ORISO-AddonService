package org.oriso.addonservice.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;

/** The authenticated user behind a request, read from the Keycloak access token. */
public record Caller(String userId, String username, Set<String> roles, Long tenantId, String accessToken) {

  /** ADR-010: a SuperAdmin is a tenant admin of the platform tenant 0. */
  public static final long PLATFORM_TENANT_ID = 0L;

  public static Caller from(Jwt jwt) {
    return new Caller(
        jwt.getSubject(),
        firstNonNull(jwt.getClaimAsString("username"), jwt.getClaimAsString("preferred_username")),
        Set.copyOf(realmRoles(jwt)),
        tenantId(jwt.getClaim("tenantId")),
        jwt.getTokenValue());
  }

  public boolean hasRole(String role) {
    return roles.contains(role);
  }

  public boolean isSuperAdmin() {
    return hasRole(Role.TENANT_ADMIN) && tenantId != null && tenantId == PLATFORM_TENANT_ID;
  }

  public boolean isAdminOfTenant(long tenant) {
    if (isSuperAdmin()) {
      return true;
    }
    boolean tenantAdmin = hasRole(Role.TENANT_ADMIN) || hasRole(Role.SINGLE_TENANT_ADMIN);
    return tenantAdmin && tenantId != null && tenantId == tenant;
  }

  private static Collection<String> realmRoles(Jwt jwt) {
    Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
    if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
      return List.of();
    }
    return roles.stream().map(String::valueOf).toList();
  }

  private static Long tenantId(Object claim) {
    if (claim instanceof Number number) {
      return number.longValue();
    }
    if (claim instanceof String text && !text.isBlank()) {
      try {
        return Long.parseLong(text.trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  private static String firstNonNull(String first, String second) {
    return first != null ? first : second;
  }
}
