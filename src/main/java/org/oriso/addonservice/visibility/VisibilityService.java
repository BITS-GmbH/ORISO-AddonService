package org.oriso.addonservice.visibility;

import jakarta.annotation.PreDestroy;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.oriso.addonservice.config.AddonProperties;
import org.oriso.addonservice.manifest.AddonManifest;
import org.oriso.addonservice.registry.RegisteredAddon;
import org.oriso.addonservice.security.Caller;
import org.oriso.addonservice.token.AddonTokenIssuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Asks add-ons which of their dynamic apps a user sees right now ({@code GET <add-on>/visibility}).
 * Add-ons are asked in parallel; one that fails or is too slow shows none of its dynamic apps.
 */
@Service
public class VisibilityService {

  private static final Logger log = LoggerFactory.getLogger(VisibilityService.class);
  private static final String PATH = "/visibility";

  private final AddonTokenIssuer tokens;
  private final AddonProperties.Visibility properties;
  private final Clock clock;
  private final RestClient client;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Map<Key, Cached> cache = new ConcurrentHashMap<>();

  public VisibilityService(AddonTokenIssuer tokens, AddonProperties properties, Clock clock) {
    this.tokens = tokens;
    this.properties = properties.visibility();
    this.clock = clock;
    var requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(this.properties.timeout()).build());
    requestFactory.setReadTimeout(this.properties.timeout());
    this.client = RestClient.builder().requestFactory(requestFactory).build();
  }

  /** Per add-on name, its dynamic apps the add-on reported as visible; empty when it failed. */
  public Map<String, Map<String, VisibleApp>> visibleApps(
      Caller caller, Context context, Collection<RegisteredAddon> addons) {
    Instant now = clock.instant();
    cache.values().removeIf(entry -> !entry.expiresAt.isAfter(now));
    var result = new HashMap<String, Map<String, VisibleApp>>();
    var pending = new LinkedHashMap<RegisteredAddon, CompletableFuture<Map<String, VisibleApp>>>();
    for (RegisteredAddon addon : addons) {
      Cached cached = cache.get(new Key(caller.userId(), addon.name(), context));
      if (cached != null) {
        result.put(addon.name(), cached.apps);
      } else {
        pending.put(
            addon,
            CompletableFuture.supplyAsync(() -> fetch(addon, caller, context), executor)
                .orTimeout(properties.timeout().toMillis(), TimeUnit.MILLISECONDS));
      }
    }
    pending.forEach(
        (addon, request) -> {
          try {
            Map<String, VisibleApp> apps = request.join();
            cache.put(
                new Key(caller.userId(), addon.name(), context),
                new Cached(apps, now.plus(properties.cacheTtl())));
            result.put(addon.name(), apps);
          } catch (RuntimeException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("Visibility of {} not available, hiding its dynamic apps: {}", addon.name(), cause.toString());
            result.put(addon.name(), Map.of());
          }
        });
    return result;
  }

  /** Drops what an add-on said about a user, in every context, e.g. after it notified them. */
  public void evict(String userId, String addon) {
    cache.keySet().removeIf(key -> key.userId().equals(userId) && key.addon().equals(addon));
  }

  private Map<String, VisibleApp> fetch(RegisteredAddon addon, Caller caller, Context context) {
    String token =
        tokens.issue(addon.name(), caller, context.agencyId(), context.sessionId(), context.askerId());
    Map<?, ?> body =
        client
            .get()
            .uri(addon.baseUrl().resolve(PATH))
            .header("Authorization", "Bearer " + token)
            .retrieve()
            .body(Map.class);
    if (body == null || !(body.get("apps") instanceof Map<?, ?> answers)) {
      throw new IllegalStateException("answer has no apps object");
    }
    var visible = new HashMap<String, VisibleApp>();
    for (AddonManifest.App app : addon.manifest().apps()) {
      if (app.isDynamic()
          && answers.get(app.id()) instanceof Map<?, ?> answer
          && Boolean.TRUE.equals(answer.get("visible"))) {
        visible.put(app.id(), new VisibleApp(badge(answer.get("badge"))));
      }
    }
    return Map.copyOf(visible);
  }

  private static Integer badge(Object value) {
    boolean integral = value instanceof Integer || value instanceof Long;
    if (!integral || ((Number) value).longValue() <= 0) {
      return null;
    }
    return (int) Math.min(((Number) value).longValue(), Integer.MAX_VALUE);
  }

  @PreDestroy
  void close() {
    executor.shutdownNow();
  }

  /** The context claims of the add-on token; part of the cache key. */
  public record Context(Long agencyId, Long sessionId, String askerId) {}

  public record VisibleApp(Integer badge) {}

  private record Key(String userId, String addon, Context context) {}

  private record Cached(Map<String, VisibleApp> apps, Instant expiresAt) {}
}
