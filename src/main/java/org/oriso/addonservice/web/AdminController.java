package org.oriso.addonservice.web;

import java.time.Instant;
import java.util.List;
import org.oriso.addonservice.enablement.AddonEnablement;
import org.oriso.addonservice.enablement.EnablementService;
import org.oriso.addonservice.manifest.AddonManifest;
import org.oriso.addonservice.registry.AddonRegistry;
import org.oriso.addonservice.registry.RegisteredAddon;
import org.oriso.addonservice.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Enablement for ORISO-Admin. Switching an add-on on or off for a Träger is for SuperAdmins
 * only; the admins of a Träger can switch it off or on again for their agencies.
 */
@RestController
@RequestMapping("/addons/admin/addons")
public class AdminController {

  private final AddonRegistry registry;
  private final EnablementService enablement;

  public AdminController(AddonRegistry registry, EnablementService enablement) {
    this.registry = registry;
    this.enablement = enablement;
  }

  @GetMapping
  public List<AdminAddon> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long tenantId) {
    Caller caller = Caller.from(jwt);
    long tenant = tenantId != null ? tenantId : requireTenant(caller);
    requireTenantAdmin(caller, tenant);
    List<AddonEnablement> switches = enablement.forTenant(tenant);
    return registry.all().stream().map(addon -> toAdminAddon(addon, tenant, switches)).toList();
  }

  @PutMapping("/{name}/tenants/{tenantId}")
  public Switch setForTenant(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String name,
      @PathVariable long tenantId,
      @RequestBody EnabledRequest request) {
    Caller caller = Caller.from(jwt);
    if (!caller.isSuperAdmin()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only platform admins enable add-ons for a Träger");
    }
    requireKnown(name);
    return Switch.of(enablement.setForTenant(name, tenantId, request.enabled(), caller.userId()));
  }

  @PutMapping("/{name}/tenants/{tenantId}/agencies/{agencyId}")
  public Switch setForAgency(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String name,
      @PathVariable long tenantId,
      @PathVariable long agencyId,
      @RequestBody EnabledRequest request) {
    Caller caller = Caller.from(jwt);
    requireTenantAdmin(caller, tenantId);
    requireKnown(name);
    return Switch.of(enablement.setForAgency(name, tenantId, agencyId, request.enabled(), caller.userId()));
  }

  @DeleteMapping("/{name}/tenants/{tenantId}/agencies/{agencyId}")
  public ResponseEntity<Void> clearAgencyOverride(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String name,
      @PathVariable long tenantId,
      @PathVariable long agencyId) {
    requireTenantAdmin(Caller.from(jwt), tenantId);
    enablement.clearAgencyOverride(name, tenantId, agencyId);
    return ResponseEntity.noContent().build();
  }

  private AdminAddon toAdminAddon(RegisteredAddon addon, long tenant, List<AddonEnablement> switches) {
    var forAddon = switches.stream().filter(s -> s.getAddonName().equals(addon.name())).toList();
    boolean tenantEnabled =
        forAddon.stream().filter(s -> s.getAgencyId() == null).anyMatch(AddonEnablement::isEnabled);
    var agencies =
        forAddon.stream().filter(s -> s.getAgencyId() != null).map(Switch::of).toList();
    return new AdminAddon(addon.manifest(), addon.source(), addon.discoveredAt(), tenant, tenantEnabled, agencies);
  }

  private void requireKnown(String name) {
    if (registry.find(name).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown add-on");
    }
  }

  private static long requireTenant(Caller caller) {
    if (caller.tenantId() == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No Träger in token");
    }
    return caller.tenantId();
  }

  private static void requireTenantAdmin(Caller caller, long tenantId) {
    if (!caller.isAdminOfTenant(tenantId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not an admin of this Träger");
    }
  }

  public record EnabledRequest(boolean enabled) {}

  public record Switch(Long tenantId, Long agencyId, boolean enabled, Instant updatedAt, String updatedBy) {
    static Switch of(AddonEnablement row) {
      return new Switch(row.getTenantId(), row.getAgencyId(), row.isEnabled(), row.getUpdatedAt(), row.getUpdatedBy());
    }
  }

  public record AdminAddon(
      AddonManifest manifest,
      String source,
      Instant discoveredAt,
      long tenantId,
      boolean enabled,
      List<Switch> agencyOverrides) {}
}
