package org.oriso.addonservice.registry;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import org.oriso.addonservice.manifest.AddonManifest;

/** An add-on the AddonService has found and whose manifest is valid. */
public record RegisteredAddon(AddonManifest manifest, URI baseUrl, String source, Instant discoveredAt) {

  public String name() {
    return manifest.name();
  }

  public Optional<AddonManifest.App> app(String id) {
    return manifest.apps().stream().filter(app -> app.id().equals(id)).findFirst();
  }
}
