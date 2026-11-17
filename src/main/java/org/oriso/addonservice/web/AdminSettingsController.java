package org.oriso.addonservice.web;

import java.util.List;
import java.util.Map;
import org.oriso.addonservice.registry.AddonClient;
import org.oriso.addonservice.registry.RegisteredAddon;
import org.oriso.addonservice.security.Caller;
import org.oriso.addonservice.settings.SettingsService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Settings and statistics of an add-on for the admins of a Träger. */
@RestController
@RequestMapping("/addons/admin/addons/{name}")
public class AdminSettingsController {

  private final AdminAddonAccess access;
  private final SettingsService settings;
  private final AddonClient addons;

  public AdminSettingsController(AdminAddonAccess access, SettingsService settings, AddonClient addons) {
    this.access = access;
    this.settings = settings;
    this.addons = addons;
  }

  @GetMapping("/settings")
  public Settings settings(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String name,
      @RequestParam long tenantId,
      @RequestParam(required = false) Long agencyId) {
    var addon = requireAdmin(jwt, name, tenantId);
    var schema = settings.schema(addon);
    return new Settings(schema.raw().isEmpty() ? null : schema.raw(), settings.stored(name, tenantId, agencyId));
  }

  @PutMapping("/settings")
  public Map<String, Object> save(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String name,
      @RequestParam long tenantId,
      @RequestParam(required = false) Long agencyId,
      @RequestBody Map<String, Object> values) {
    var addon = requireAdmin(jwt, name, tenantId);
    return settings.save(addon, tenantId, agencyId, values, Caller.from(jwt).userId());
  }

  @GetMapping("/stats")
  public Statistics stats(@AuthenticationPrincipal Jwt jwt, @PathVariable String name, @RequestParam long tenantId) {
    var addon = requireAdmin(jwt, name, tenantId);
    Map<String, Object> values = addons.statistics(addon, tenantId);
    List<Statistic> stats =
        addon.manifest().stats().stream()
            .map(stat -> new Statistic(stat.id(), stat.label() == null ? Map.of() : stat.label(), stat.type(), number(values.get(stat.id()))))
            .toList();
    return new Statistics(stats);
  }

  private RegisteredAddon requireAdmin(Jwt jwt, String name, long tenantId) {
    return access.require(Caller.from(jwt), name, tenantId);
  }

  private static Number number(Object value) {
    return value instanceof Number number ? number : null;
  }

  public record Settings(Map<String, Object> schema, Map<String, Object> values) {}

  public record Statistic(String id, Map<String, String> label, String type, Number value) {}

  public record Statistics(List<Statistic> stats) {}
}
