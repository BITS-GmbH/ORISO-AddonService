package org.oriso.addonservice.discovery;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.oriso.addonservice.config.AddonProperties;
import org.oriso.addonservice.manifest.AddonManifest;
import org.oriso.addonservice.manifest.ManifestValidator;
import org.oriso.addonservice.registry.AddonRegistry;
import org.oriso.addonservice.registry.RegisteredAddon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Finds add-ons (Kubernetes label and static list) and keeps the registry up to date. */
@Component
public class AddonDiscovery {

  static final String MANIFEST_PATH = "/.well-known/oriso-addon.json";

  private static final Logger log = LoggerFactory.getLogger(AddonDiscovery.class);

  private final AddonProperties.Discovery properties;
  private final AddonRegistry registry;
  private final Clock clock;
  private final RestClient manifestClient;
  private final KubernetesServiceLister kubernetes;

  public AddonDiscovery(AddonProperties properties, AddonRegistry registry, Clock clock) {
    this.properties = properties.discovery();
    this.registry = registry;
    this.clock = clock;
    var requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(this.properties.manifestTimeout()).build());
    requestFactory.setReadTimeout(this.properties.manifestTimeout());
    this.manifestClient = RestClient.builder().requestFactory(requestFactory).build();
    this.kubernetes = new KubernetesServiceLister(this.properties);
  }

  @Scheduled(
      initialDelayString = "${addons.discovery.initial-delay-ms:0}",
      fixedDelayString = "${addons.discovery.interval-ms:30000}")
  public void refresh() {
    Map<String, RegisteredAddon> found = new LinkedHashMap<>();
    for (URI baseUrl : candidates()) {
      register(baseUrl, found);
    }
    registry.replaceAll(found);
  }

  private Set<URI> candidates() {
    Set<URI> candidates = new LinkedHashSet<>(properties.staticAddons());
    if (properties.kubernetes()) {
      try {
        candidates.addAll(kubernetes.list());
      } catch (Exception e) {
        log.warn("Listing add-on Services failed, keeping static add-ons only: {}", e.getMessage());
      }
    }
    return candidates;
  }

  private void register(URI baseUrl, Map<String, RegisteredAddon> found) {
    AddonManifest manifest;
    try {
      manifest = manifestClient.get().uri(baseUrl.resolve(MANIFEST_PATH)).retrieve().body(AddonManifest.class);
    } catch (Exception e) {
      log.warn("No manifest at {}: {}", baseUrl, e.getMessage());
      return;
    }
    if (manifest == null) {
      log.warn("Empty manifest at {}", baseUrl);
      return;
    }
    var problems = ManifestValidator.problems(manifest);
    if (!problems.isEmpty()) {
      log.warn("Ignoring add-on at {}: {}", baseUrl, String.join("; ", problems));
      return;
    }
    if (found.containsKey(manifest.name())) {
      log.warn(
          "Ignoring add-on at {}: name '{}' is already served by {}",
          baseUrl,
          manifest.name(),
          found.get(manifest.name()).baseUrl());
      return;
    }
    String source = properties.staticAddons().contains(baseUrl) ? "static" : "kubernetes";
    found.put(manifest.name(), new RegisteredAddon(manifest, baseUrl, source, clock.instant()));
  }
}
