package org.oriso.addonservice.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.oriso.addonservice.config.AddonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Checks that a counsellor may see a session, by asking UserService with the counsellor's own
 * token, and returns the client of that session. UserService answers 403 for foreign sessions.
 */
@Component
public class SessionAccess {

  private static final Logger log = LoggerFactory.getLogger(SessionAccess.class);

  private final RestClient userService;
  private final AddonProperties.UserService properties;
  private final Clock clock;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  public SessionAccess(AddonProperties properties, Clock clock) {
    this.properties = properties.userService();
    this.userService = RestClient.builder().baseUrl(this.properties.baseUrl().toString()).build();
    this.clock = clock;
  }

  /** The client (asker) of the session, or empty when the caller may not see it. */
  public Optional<String> askerOf(Caller caller, long sessionId) {
    Instant now = clock.instant();
    cache.values().removeIf(entry -> entry.expiresAt.isBefore(now));
    String key = caller.accessToken() + "#" + sessionId;
    Cached cached = cache.get(key);
    if (cached != null) {
      return cached.askerId;
    }
    Optional<String> askerId = fetch(caller, sessionId);
    cache.put(key, new Cached(askerId, now.plus(properties.cacheTtl())));
    return askerId;
  }

  private Optional<String> fetch(Caller caller, long sessionId) {
    try {
      Map<?, ?> session =
          userService
              .get()
              .uri("/users/consultants/sessions/{id}", sessionId)
              .header("Authorization", "Bearer " + caller.accessToken())
              .retrieve()
              .body(Map.class);
      return session != null && session.get("askerId") instanceof String askerId
          ? Optional.of(askerId)
          : Optional.empty();
    } catch (RestClientResponseException e) {
      HttpStatusCode status = e.getStatusCode();
      if (status.value() != 403 && status.value() != 404) {
        log.warn("UserService answered {} for session {}", status.value(), sessionId);
      }
      return Optional.empty();
    } catch (RestClientException e) {
      log.warn("Could not check session {} with UserService: {}", sessionId, e.getMessage());
      return Optional.empty();
    }
  }

  private record Cached(Optional<String> askerId, Instant expiresAt) {}
}
