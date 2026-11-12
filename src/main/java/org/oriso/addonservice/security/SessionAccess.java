package org.oriso.addonservice.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.oriso.addonservice.config.AddonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Checks that a counsellor may see a session, by asking UserService with the counsellor's own
 * token, and returns the client and agency of that session. UserService answers 403 for foreign
 * sessions.
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

  /** The session's client (asker) and agency, or empty when the caller may not see it. */
  public Optional<SessionInfo> find(Caller caller, long sessionId) {
    Instant now = clock.instant();
    cache.values().removeIf(entry -> entry.expiresAt.isBefore(now));
    String key = caller.accessToken() + "#" + sessionId;
    Cached cached = cache.get(key);
    if (cached != null) {
      return cached.session;
    }
    Optional<SessionInfo> session = fetch(caller, sessionId);
    cache.put(key, new Cached(session, now.plus(properties.cacheTtl())));
    return session;
  }

  private Optional<SessionInfo> fetch(Caller caller, long sessionId) {
    try {
      Map<?, ?> session =
          userService
              .get()
              .uri("/users/consultants/sessions/{id}", sessionId)
              .header("Authorization", "Bearer " + caller.accessToken())
              .retrieve()
              .body(Map.class);
      if (session == null || !(session.get("askerId") instanceof String askerId)) {
        return Optional.empty();
      }
      Long agencyId = session.get("agencyId") instanceof Number number ? number.longValue() : null;
      return Optional.of(new SessionInfo(askerId, agencyId));
    } catch (RestClientResponseException e) {
      HttpStatusCode status = e.getStatusCode();
      if (status.value() == 403 || status.value() == 404) {
        return Optional.empty();
      }
      log.warn("UserService answered {} for session {}", status.value(), sessionId);
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Session check failed");
    } catch (RestClientException e) {
      log.warn("Could not check session {} with UserService: {}", sessionId, e.getMessage());
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Session check failed");
    }
  }

  public record SessionInfo(String askerId, Long agencyId) {}

  private record Cached(Optional<SessionInfo> session, Instant expiresAt) {}
}
