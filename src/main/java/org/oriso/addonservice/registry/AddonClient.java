package org.oriso.addonservice.registry;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.oriso.addonservice.config.AddonProperties;
import org.oriso.addonservice.token.AddonTokenIssuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Calls the AddonService makes to an add-on itself: settings schema and statistics. */
@Component
public class AddonClient {

  private static final Logger log = LoggerFactory.getLogger(AddonClient.class);
  private static final Duration SCHEMA_CACHE = Duration.ofMinutes(5);

  private final RestClient client;
  private final AddonTokenIssuer tokens;
  private final Clock clock;
  private final Map<String, CachedSchema> schemas = new ConcurrentHashMap<>();

  public AddonClient(AddonProperties properties, AddonTokenIssuer tokens, Clock clock) {
    var requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(properties.gateway().connectTimeout()).build());
    requestFactory.setReadTimeout(properties.discovery().manifestTimeout());
    this.client = RestClient.builder().requestFactory(requestFactory).build();
    this.tokens = tokens;
    this.clock = clock;
  }

  /** The JSON Schema of the add-on's settings, or empty when it declares none. */
  @SuppressWarnings("unchecked")
  public Optional<Map<String, Object>> settingsSchema(RegisteredAddon addon) {
    String path = addon.manifest().settings();
    if (path == null || path.isBlank()) {
      return Optional.empty();
    }
    String key = addon.name() + "@" + addon.manifest().version();
    Instant now = clock.instant();
    CachedSchema cached = schemas.get(key);
    if (cached != null && cached.loadedAt.plus(SCHEMA_CACHE).isAfter(now)) {
      return Optional.of(cached.schema);
    }
    try {
      Map<String, Object> schema = client.get().uri(addon.baseUrl().resolve(path)).retrieve().body(Map.class);
      if (schema == null) {
        return Optional.empty();
      }
      schemas.put(key, new CachedSchema(schema, now));
      return Optional.of(schema);
    } catch (RestClientException e) {
      log.warn("Settings schema of {} not available: {}", addon.name(), e.getMessage());
      return cached == null ? Optional.empty() : Optional.of(cached.schema);
    }
  }

  /** Values of the add-on's statistics for one Träger; empty when the add-on does not answer. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> statistics(RegisteredAddon addon, long tenantId) {
    try {
      Map<String, Object> body =
          client
              .get()
              .uri(addon.baseUrl().resolve("/stats?tenantId=" + tenantId))
              .header("Authorization", "Bearer " + tokens.issueForService(addon.name(), tenantId))
              .retrieve()
              .body(Map.class);
      return body != null && body.get("values") instanceof Map<?, ?> values ? (Map<String, Object>) values : Map.of();
    } catch (RestClientException e) {
      log.warn("Statistics of {} not available: {}", addon.name(), e.getMessage());
      return Map.of();
    }
  }

  private record CachedSchema(Map<String, Object> schema, Instant loadedAt) {}
}
