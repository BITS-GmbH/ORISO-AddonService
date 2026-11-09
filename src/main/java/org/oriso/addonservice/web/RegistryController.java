package org.oriso.addonservice.web;

import java.util.List;
import java.util.Map;
import org.oriso.addonservice.config.AddonProperties;
import org.oriso.addonservice.enablement.EnablementService;
import org.oriso.addonservice.manifest.AddonManifest;
import org.oriso.addonservice.manifest.Slot;
import org.oriso.addonservice.registry.AddonRegistry;
import org.oriso.addonservice.security.Caller;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** What the web app and ORISO-Admin load to know which add-on apps to show where. */
@RestController
public class RegistryController {

  private final AddonRegistry registry;
  private final EnablementService enablement;
  private final AddonAccess access;
  private final String publicPath;

  public RegistryController(
      AddonRegistry registry, EnablementService enablement, AddonAccess access, AddonProperties properties) {
    this.registry = registry;
    this.enablement = enablement;
    this.access = access;
    this.publicPath = properties.publicPath();
  }

  @GetMapping("/addons/registry")
  public Registry registry(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = AddonAccess.AGENCY_HEADER, required = false) String agencyHeader) {
    Caller caller = Caller.from(jwt);
    Long agencyId = access.agencyContext(caller, agencyHeader);
    List<AvailableAddon> available =
        registry.all().stream()
            .filter(addon -> enablement.isEnabled(addon.name(), caller.tenantId(), agencyId))
            .map(addon -> toAvailable(addon.manifest(), caller))
            .filter(addon -> !addon.apps().isEmpty())
            .toList();
    return new Registry(AddonManifest.CONTRACT_V1, available);
  }

  private AvailableAddon toAvailable(AddonManifest manifest, Caller caller) {
    List<AvailableApp> apps =
        manifest.apps().stream()
            .map(
                app ->
                    new AvailableApp(
                        app.id(),
                        app.title() == null ? Map.of() : app.title(),
                        app.slots().stream()
                            .filter(slot -> Slot.fromId(slot).map(s -> s.isVisibleTo(caller)).orElse(false))
                            .toList()))
            .filter(app -> !app.slots().isEmpty())
            .toList();
    String base = publicPath + "/" + manifest.name();
    return new AvailableAddon(
        manifest.name(), manifest.version(), manifest.react(), base + manifest.ui(), base + "/api", apps);
  }

  public record Registry(String contract, List<AvailableAddon> addons) {}

  public record AvailableAddon(
      String name, String version, String react, String entry, String apiBase, List<AvailableApp> apps) {}

  public record AvailableApp(String id, Map<String, String> title, List<String> slots) {}
}
