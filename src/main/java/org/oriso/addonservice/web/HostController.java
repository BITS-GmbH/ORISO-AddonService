package org.oriso.addonservice.web;

import java.util.List;
import java.util.Map;
import org.oriso.addonservice.registry.AddonRegistry;
import org.oriso.addonservice.settings.SettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Host API for add-on backends. They authenticate with the token the AddonService gave them, so
 * every answer is scoped to the add-on, Träger and agency of that token.
 */
@RestController
public class HostController {

  private final AddonRegistry registry;
  private final SettingsService settings;

  public HostController(AddonRegistry registry, SettingsService settings) {
    this.registry = registry;
    this.settings = settings;
  }

  @GetMapping("/addons/host/settings")
  public Map<String, Object> settings(@AuthenticationPrincipal Jwt token) {
    List<String> audience = token.getAudience();
    if (audience == null || audience.size() != 1) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Token is not for one add-on");
    }
    var addon =
        registry
            .find(audience.getFirst())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown add-on"));
    Long tenantId = number(token.getClaim("tenantId"));
    if (tenantId == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No Träger in token");
    }
    return settings.effective(addon, tenantId, number(token.getClaim("agencyId")));
  }

  private static Long number(Object claim) {
    return claim instanceof Number number ? number.longValue() : null;
  }
}
