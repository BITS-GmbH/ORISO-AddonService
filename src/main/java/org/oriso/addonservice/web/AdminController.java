package org.oriso.addonservice.web;

import static org.oriso.addonservice.web.AdminAddonAccess.requireSuperAdmin;
import static org.oriso.addonservice.web.AdminAddonAccess.requireTenantAdmin;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.oriso.addonservice.config.AddonProperties;
import org.oriso.addonservice.enablement.AddonEnablement;
import org.oriso.addonservice.enablement.EnablementService;
import org.oriso.addonservice.enablement.TenantMode;
import org.oriso.addonservice.manifest.AddonManifest;
import org.oriso.addonservice.readme.ReadmeService;
import org.oriso.addonservice.registry.AddonRegistry;
import org.oriso.addonservice.registry.RegisteredAddon;
import org.oriso.addonservice.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * Enablement for ORISO-Admin. A SuperAdmin hands an add-on to a Träger in a {@link TenantMode};
 * the admins of the Träger see it in mode TENANT or ON, switch it themselves in mode TENANT and
 * switch it off or on again for their agencies.
 */
@RestController
@RequestMapping("/addons/admin/addons")
public class AdminController {

  private static final MediaType MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);

  private final AddonRegistry registry;
  private final EnablementService enablement;
  private final AdminAddonAccess access;
  private final ReadmeService readmes;
  private final String publicPath;

  public AdminController(
      AddonRegistry registry,
      EnablementService enablement,
      AdminAddonAccess access,
      ReadmeService readmes,
      AddonProperties properties) {
    this.registry = registry;
    this.enablement = enablement;
    this.access = access;
    this.readmes = readmes;
    this.publicPath = properties.publicPath();
  }

  @GetMapping
  public List<AdminAddon> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long tenantId) {
    Caller caller = Caller.from(jwt);
    long tenant = tenantId != null ? tenantId : requireTenant(caller);
    requireTenantAdmin(caller, tenant);
    List<AddonEnablement> switches = enablement.forTenant(tenant);
    return registry.all().stream()
        .filter(addon -> access.isVisible(caller, addon.name(), tenant))
        .map(addon -> toAdminAddon(caller, addon, tenant, switches))
        .toList();
  }

  /**
   * The Träger an add-on has a switch or agency overrides for, for the add-on centred view in
   * ORISO-Admin. SuperAdmins only: it shows every Träger.
   */
  @GetMapping("/{name}/tenants")
  public List<TenantState> tenants(@AuthenticationPrincipal Jwt jwt, @PathVariable String name) {
    requireSuperAdmin(Caller.from(jwt), "Only platform admins see all Träger");
    access.requireKnown(name);
    Map<Long, List<AddonEnablement>> byTenant =
        enablement.forAddon(name).stream()
            .collect(Collectors.groupingBy(AddonEnablement::getTenantId, TreeMap::new, Collectors.toList()));
    return byTenant.entrySet().stream().map(entry -> TenantState.of(entry.getKey(), entry.getValue())).toList();
  }

  /** The platform-wide switch; off until a SuperAdmin switches it on, and then above every Träger. */
  @PutMapping("/{name}/global")
  public GlobalSwitch setGlobal(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String name, @RequestBody EnabledRequest request) {
    Caller caller = Caller.from(jwt);
    requireSuperAdmin(caller, "Only platform admins switch an add-on globally");
    access.requireKnown(name);
    var row = enablement.setGlobal(name, request.enabled(), caller.userId());
    return new GlobalSwitch(row.isEnabled(), row.getUpdatedAt(), row.getUpdatedBy());
  }

  @PutMapping("/{name}/tenants/{tenantId}/mode")
  public Switch setMode(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String name,
      @PathVariable long tenantId,
      @RequestBody ModeRequest request) {
    Caller caller = Caller.from(jwt);
    requireSuperAdmin(caller, "Only platform admins set the mode of an add-on for a Träger");
    access.requireKnown(name);
    TenantMode mode =
        TenantMode.parse(request.mode())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown mode"));
    return Switch.of(enablement.setMode(name, tenantId, mode, caller.userId()));
  }

  /** SuperAdmins always; the admins of the Träger only in mode TENANT. */
  @PutMapping("/{name}/tenants/{tenantId}")
  public Switch setForTenant(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String name,
      @PathVariable long tenantId,
      @RequestBody EnabledRequest request) {
    Caller caller = Caller.from(jwt);
    access.require(caller, name, tenantId);
    if (!caller.isSuperAdmin() && enablement.tenantMode(name, tenantId) != TenantMode.TENANT) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The Träger cannot switch this add-on");
    }
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
    access.require(caller, name, tenantId);
    return Switch.of(enablement.setForAgency(name, tenantId, agencyId, request.enabled(), caller.userId()));
  }

  @DeleteMapping("/{name}/tenants/{tenantId}/agencies/{agencyId}")
  public ResponseEntity<Void> clearAgencyOverride(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String name,
      @PathVariable long tenantId,
      @PathVariable long agencyId) {
    access.require(Caller.from(jwt), name, tenantId);
    enablement.clearAgencyOverride(name, tenantId, agencyId);
    return ResponseEntity.noContent().build();
  }

  /** The add-on's README as Markdown with absolute links; the caller renders and sanitizes it. */
  @GetMapping("/{name}/readme")
  public ResponseEntity<String> readme(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String name, @RequestParam(required = false) Long tenantId) {
    Caller caller = Caller.from(jwt);
    long tenant = tenantId != null ? tenantId : requireTenant(caller);
    RegisteredAddon addon = access.require(caller, name, tenant);
    String markdown =
        readmes
            .readme(addon)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No README"));
    return ResponseEntity.ok().contentType(MARKDOWN).body(markdown);
  }

  private AdminAddon toAdminAddon(Caller caller, RegisteredAddon addon, long tenant, List<AddonEnablement> switches) {
    var forAddon = switches.stream().filter(s -> s.getAddonName().equals(addon.name())).toList();
    var tenantSwitch = forAddon.stream().filter(s -> s.getAgencyId() == null).findFirst();
    TenantMode mode = tenantSwitch.map(AddonEnablement::getMode).orElse(TenantMode.OFF);
    boolean tenantEnabled = tenantSwitch.map(AddonEnablement::isOnForTenant).orElse(false);
    var agencies =
        forAddon.stream().filter(s -> s.getAgencyId() != null).map(Switch::of).toList();
    var global =
        enablement
            .globalSwitch(addon.name())
            .map(row -> new GlobalSwitch(row.isEnabled(), row.getUpdatedAt(), row.getUpdatedBy()))
            .orElse(new GlobalSwitch(false, null, null));
    return new AdminAddon(
        addon.manifest().withPublicIcons(publicPath),
        addon.source(),
        addon.discoveredAt(),
        global,
        tenant,
        tenantEnabled,
        mode.name(),
        caller.isSuperAdmin() || mode == TenantMode.TENANT,
        addon.manifest().hasReadme(),
        agencies);
  }

  private static long requireTenant(Caller caller) {
    if (caller.tenantId() == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No Träger in token");
    }
    return caller.tenantId();
  }

  public record EnabledRequest(boolean enabled) {}

  public record ModeRequest(String mode) {}

  /** {@code mode} only for the switch of a Träger, null for an agency override. */
  public record Switch(
      Long tenantId, Long agencyId, boolean enabled, String mode, Instant updatedAt, String updatedBy) {
    static Switch of(AddonEnablement row) {
      TenantMode mode = row.getMode();
      boolean enabled = mode == null ? row.isEnabled() : row.isOnForTenant();
      return new Switch(
          row.getTenantId(),
          row.getAgencyId(),
          enabled,
          mode == null ? null : mode.name(),
          row.getUpdatedAt(),
          row.getUpdatedBy());
    }
  }

  /**
   * One Träger of an add-on: its mode and whether that makes it on for the Träger ({@code OFF},
   * {@code enabled} false and no {@code updatedAt} when only agency overrides exist) and how many
   * agencies are switched on or off on their own.
   */
  public record TenantState(
      long tenantId,
      boolean enabled,
      String mode,
      Instant updatedAt,
      String updatedBy,
      long agenciesEnabled,
      long agenciesDisabled) {
    static TenantState of(long tenantId, List<AddonEnablement> rows) {
      var tenantSwitch = rows.stream().filter(row -> row.getAgencyId() == null).findFirst();
      var agencies = rows.stream().filter(row -> row.getAgencyId() != null).toList();
      return new TenantState(
          tenantId,
          tenantSwitch.map(AddonEnablement::isOnForTenant).orElse(false),
          tenantSwitch.map(AddonEnablement::getMode).orElse(TenantMode.OFF).name(),
          tenantSwitch.map(AddonEnablement::getUpdatedAt).orElse(null),
          tenantSwitch.map(AddonEnablement::getUpdatedBy).orElse(null),
          agencies.stream().filter(AddonEnablement::isEnabled).count(),
          agencies.stream().filter(row -> !row.isEnabled()).count());
    }
  }

  /** {@code enabled} false and no {@code updatedAt} while nobody has switched it yet. */
  public record GlobalSwitch(boolean enabled, Instant updatedAt, String updatedBy) {}

  /**
   * {@code enabled} is whether the Träger level is on (mode ON or ON_HIDDEN, or TENANT with the
   * Träger's switch on); the add-on runs only while {@code global} is on too. {@code
   * tenantCanSwitch} tells whether the caller may change {@code enabled}.
   */
  public record AdminAddon(
      AddonManifest manifest,
      String source,
      Instant discoveredAt,
      GlobalSwitch global,
      long tenantId,
      boolean enabled,
      String mode,
      boolean tenantCanSwitch,
      boolean hasReadme,
      List<Switch> agencyOverrides) {}
}
