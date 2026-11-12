package org.oriso.addonservice.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import org.oriso.addonservice.config.AddonProperties;
import org.oriso.addonservice.manifest.AddonManifest;
import org.oriso.addonservice.manifest.Slot;
import org.oriso.addonservice.registry.AddonRegistry;
import org.oriso.addonservice.registry.RegisteredAddon;
import org.oriso.addonservice.security.Caller;
import org.oriso.addonservice.visibility.VisibilityService;
import org.oriso.addonservice.visibility.VisibilityService.VisibleApp;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the web app and ORISO-Admin load to know which add-on apps to show where: enablement in
 * the request's agency context, slots for the caller's role, then the state of dynamic apps.
 */
@RestController
public class RegistryController {

  private final AddonRegistry registry;
  private final AddonAccess access;
  private final VisibilityService visibility;
  private final String publicPath;

  public RegistryController(
      AddonRegistry registry, AddonAccess access, VisibilityService visibility, AddonProperties properties) {
    this.registry = registry;
    this.access = access;
    this.visibility = visibility;
    this.publicPath = properties.publicPath();
  }

  @GetMapping("/addons/registry")
  public Registry registry(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = AddonAccess.AGENCY_HEADER, required = false) String agencyHeader,
      @RequestHeader(value = AddonAccess.SESSION_HEADER, required = false) String sessionHeader) {
    Caller caller = Caller.from(jwt);
    AddonAccess.RegistryContext context = access.registryContext(caller, agencyHeader, sessionHeader);
    List<RegisteredAddon> enabled =
        registry.all().stream()
            .filter(addon -> access.isEnabled(addon.name(), caller, context))
            .filter(addon -> !visibleApps(addon.manifest(), caller).isEmpty())
            .toList();
    List<RegisteredAddon> dynamic =
        enabled.stream()
            .filter(addon -> visibleApps(addon.manifest(), caller).stream().anyMatch(AddonManifest.App::isDynamic))
            .toList();
    Map<String, Map<String, VisibleApp>> states =
        dynamic.isEmpty() ? Map.of() : visibility.visibleApps(caller, tokenContext(context), dynamic);
    List<AvailableAddon> available =
        enabled.stream()
            .map(addon -> toAvailable(addon.manifest(), caller, states.getOrDefault(addon.name(), Map.of())))
            .filter(addon -> !addon.apps().isEmpty())
            .toList();
    return new Registry(AddonManifest.CONTRACT_V1, available);
  }

  private static VisibilityService.Context tokenContext(AddonAccess.RegistryContext context) {
    var session = context.session();
    return new VisibilityService.Context(
        context.agencyId(), session == null ? null : session.sessionId(), session == null ? null : session.askerId());
  }

  private static List<AddonManifest.App> visibleApps(AddonManifest manifest, Caller caller) {
    return manifest.apps().stream().filter(app -> !visibleSlots(app, caller).isEmpty()).toList();
  }

  private static List<String> visibleSlots(AddonManifest.App app, Caller caller) {
    return app.slots().stream()
        .filter(slot -> Slot.fromId(slot).map(s -> s.isVisibleTo(caller)).orElse(false))
        .toList();
  }

  private AvailableAddon toAvailable(AddonManifest manifest, Caller caller, Map<String, VisibleApp> dynamicStates) {
    List<AvailableApp> apps =
        visibleApps(manifest, caller).stream()
            .filter(app -> !app.isDynamic() || dynamicStates.containsKey(app.id()))
            .map(
                app ->
                    new AvailableApp(
                        app.id(),
                        app.title() == null ? Map.of() : app.title(),
                        visibleSlots(app, caller),
                        app.isDynamic() ? dynamicStates.get(app.id()).badge() : null))
            .toList();
    String base = publicPath + "/" + manifest.name();
    return new AvailableAddon(
        manifest.name(), manifest.version(), manifest.react(), base + manifest.ui(), base + "/api", apps);
  }

  public record Registry(String contract, List<AvailableAddon> addons) {}

  public record AvailableAddon(
      String name, String version, String react, String entry, String apiBase, List<AvailableApp> apps) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record AvailableApp(String id, Map<String, String> title, List<String> slots, Integer badge) {}
}
