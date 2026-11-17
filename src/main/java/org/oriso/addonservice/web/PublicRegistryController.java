package org.oriso.addonservice.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import org.oriso.addonservice.config.AddonProperties;
import org.oriso.addonservice.manifest.AddonManifest;
import org.oriso.addonservice.registration.RegistrationAddons;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** What the registration loads, without login, to know whether an add-on chooses the agency. */
@RestController
public class PublicRegistryController {

  private final RegistrationAddons registration;
  private final String publicPath;

  public PublicRegistryController(RegistrationAddons registration, AddonProperties properties) {
    this.registration = registration;
    this.publicPath = properties.publicPath();
  }

  @GetMapping("/addons/public/registration")
  public ResponseEntity<RegistrationAddon> registration(@RequestParam long tenantId) {
    return registration
        .selected(tenantId)
        .map(
            finder -> {
              var manifest = finder.addon().manifest();
              String base = publicPath + "/" + manifest.name();
              return ResponseEntity.ok(
                  new RegistrationAddon(
                      AddonManifest.CONTRACT_V1,
                      manifest.name(),
                      manifest.version(),
                      manifest.react(),
                      finder.app().id(),
                      finder.app().title() == null ? Map.of() : finder.app().title(),
                      finder.app().icon() == null ? null : base + finder.app().icon(),
                      base + manifest.ui(),
                      base + "/public/api"));
            })
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record RegistrationAddon(
      String contract,
      String name,
      String version,
      String react,
      String appId,
      Map<String, String> title,
      String icon,
      String entry,
      String apiBase) {}
}
