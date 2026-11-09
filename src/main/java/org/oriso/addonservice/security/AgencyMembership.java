package org.oriso.addonservice.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.oriso.addonservice.config.AddonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Resolves the agencies a caller belongs to by asking the core UserService with the caller's
 * own token. The access token carries no agencies, so this is the only reliable source.
 */
@Component
public class AgencyMembership {

  private static final Logger log = LoggerFactory.getLogger(AgencyMembership.class);

  private final RestClient userService;
  private final AddonProperties.UserService properties;
  private final Clock clock;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  public AgencyMembership(AddonProperties properties, Clock clock) {
    this.properties = properties.userService();
    this.userService = RestClient.builder().baseUrl(this.properties.baseUrl().toString()).build();
    this.clock = clock;
  }

  public boolean belongsTo(Caller caller, long agencyId) {
    return agenciesOf(caller).contains(agencyId);
  }

  public Set<Long> agenciesOf(Caller caller) {
    Instant now = clock.instant();
    cache.values().removeIf(entry -> entry.expiresAt.isBefore(now));
    Cached cached = cache.get(caller.accessToken());
    if (cached != null) {
      return cached.agencies;
    }
    Set<Long> agencies = fetch(caller);
    if (agencies != null) {
      cache.put(caller.accessToken(), new Cached(agencies, now.plus(properties.cacheTtl())));
      return agencies;
    }
    return Set.of();
  }

  /** Returns null when UserService could not be asked, so the failure is not cached. */
  private Set<Long> fetch(Caller caller) {
    try {
      Map<?, ?> userData =
          userService
              .get()
              .uri(properties.userDataPath())
              .header("Authorization", "Bearer " + caller.accessToken())
              .retrieve()
              .body(Map.class);
      if (userData == null || !(userData.get("agencies") instanceof Collection<?> agencies)) {
        return Set.of();
      }
      return agencies.stream()
          .filter(Map.class::isInstance)
          .map(agency -> ((Map<?, ?>) agency).get("id"))
          .filter(Number.class::isInstance)
          .map(id -> ((Number) id).longValue())
          .collect(Collectors.toUnmodifiableSet());
    } catch (RestClientException e) {
      log.warn("Could not load agencies of user {} from UserService: {}", caller.userId(), e.getMessage());
      return null;
    }
  }

  private record Cached(Set<Long> agencies, Instant expiresAt) {}
}
