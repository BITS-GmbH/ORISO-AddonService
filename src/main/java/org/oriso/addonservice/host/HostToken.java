package org.oriso.addonservice.host;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** The claims of an add-on token, as an add-on backend presents it to the host API. */
public record HostToken(
    String addon,
    String userId,
    String username,
    List<String> roles,
    Long tenantId,
    Long agencyId,
    Long sessionId,
    String askerId) {

  public static HostToken from(Jwt jwt) {
    List<String> audience = jwt.getAudience();
    if (audience == null || audience.size() != 1) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Token is not for one add-on");
    }
    List<String> roles = jwt.getClaimAsStringList("roles");
    return new HostToken(
        audience.getFirst(),
        jwt.getSubject(),
        jwt.getClaimAsString("username"),
        roles == null ? List.of() : List.copyOf(roles),
        number(jwt.getClaim("tenantId")),
        number(jwt.getClaim("agencyId")),
        number(jwt.getClaim("sessionId")),
        jwt.getClaimAsString("askerId"));
  }

  public long requireTenant() {
    if (tenantId == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No Träger in token");
    }
    return tenantId;
  }

  private static Long number(Object claim) {
    return claim instanceof Number number ? number.longValue() : null;
  }
}
