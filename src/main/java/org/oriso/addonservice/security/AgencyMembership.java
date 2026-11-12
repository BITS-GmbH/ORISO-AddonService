package org.oriso.addonservice.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.oriso.addonservice.config.AddonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the agencies a caller belongs to by asking the core UserService with the caller's
 * own token. The access token carries no agencies, so this is the only reliable source.
 * Counsellors get {@code agencies[].id}; clients get no such list, their agencies are those of
 * their sessions ({@code sessions[].agencyId}) and of their registrations
 * ({@code consultingTypes.*.agency.id}).
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
    Set<Long> agencies = load(caller);
    return agencies == null ? Set.of() : agencies;
  }

  /** Like {@link #agenciesOf}, but a failing UserService is a 502 instead of "no agencies". */
  public Set<Long> requireAgenciesOf(Caller caller) {
    Set<Long> agencies = load(caller);
    if (agencies == null) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Agency lookup failed");
    }
    return agencies;
  }

  private Set<Long> load(Caller caller) {
    Instant now = clock.instant();
    cache.values().removeIf(entry -> entry.expiresAt.isBefore(now));
    Cached cached = cache.get(caller.accessToken());
    if (cached != null) {
      return cached.agencies;
    }
    Set<Long> agencies = fetch(caller);
    if (agencies != null) {
      cache.put(caller.accessToken(), new Cached(agencies, now.plus(properties.cacheTtl())));
    }
    return agencies;
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
      return userData == null ? Set.of() : agencyIds(userData);
    } catch (RestClientException e) {
      log.warn("Could not load agencies of user {} from UserService: {}", caller.userId(), e.getMessage());
      return null;
    }
  }

  private static Set<Long> agencyIds(Map<?, ?> userData) {
    Stream<Object> counsellorAgencies = elements(userData.get("agencies")).map(agency -> agency.get("id"));
    Stream<Object> sessionAgencies = elements(userData.get("sessions")).map(session -> session.get("agencyId"));
    Stream<Object> registeredAgencies =
        userData.get("consultingTypes") instanceof Map<?, ?> types
            ? types.values().stream()
                .filter(Map.class::isInstance)
                .map(type -> ((Map<?, ?>) type).get("agency"))
                .filter(Map.class::isInstance)
                .map(agency -> ((Map<?, ?>) agency).get("id"))
            : Stream.empty();
    return Stream.of(counsellorAgencies, sessionAgencies, registeredAgencies)
        .flatMap(ids -> ids)
        .filter(Number.class::isInstance)
        .map(id -> ((Number) id).longValue())
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Stream<Map<?, ?>> elements(Object list) {
    return list instanceof Collection<?> items
        ? items.stream().filter(Map.class::isInstance).map(item -> (Map<?, ?>) item)
        : Stream.empty();
  }

  private record Cached(Set<Long> agencies, Instant expiresAt) {}
}
