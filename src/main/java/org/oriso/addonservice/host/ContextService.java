package org.oriso.addonservice.host;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.oriso.addonservice.config.AddonProperties;
import org.oriso.addonservice.security.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Who a person is in counselling, from UserService: their sessions with agency and counsellor,
 * their agencies and the counsellors involved. Always within the Träger of the token; with an
 * agency in the token, only that agency's sessions and counsellors.
 */
@Service
public class ContextService {

  public static final String SELF = "self";
  public static final String ASKER = "asker";

  private final AddonProperties.UserService properties;
  private final CoreReadClient core;
  private final Clock clock;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  public ContextService(AddonProperties properties, CoreReadClient core, Clock clock) {
    this.properties = properties.userService();
    this.core = core;
    this.clock = clock;
  }

  /**
   * {@code subject} is {@code self} (the token's user) or {@code asker} (the client of the
   * token's session, verified by the AddonService when the token was issued). Without a subject,
   * the token's client is meant if it has one, else the token's user.
   */
  public Map<String, Object> context(HostToken token, String subject) {
    long tenantId = token.requireTenant();
    String userId = subjectId(token, subject);
    Map<String, Object> context = read(userId, tenantId);
    return token.agencyId() == null ? context : withinAgency(context, token.agencyId());
  }

  /** The counsellors of a client, for notifications: id and the session they counsel the client in. */
  public List<Counsellor> counsellorsOf(HostToken token) {
    String askerId = token.askerId() != null && !token.askerId().isBlank() ? token.askerId() : null;
    if (askerId == null && token.roles().contains(Role.ASKER)) {
      askerId = token.userId();
    }
    if (askerId == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Token has no client whose counsellors to notify");
    }
    Map<String, Object> context = read(askerId, token.requireTenant());
    if (token.agencyId() != null) {
      context = withinAgency(context, token.agencyId());
    }
    var counsellors = new ArrayList<Counsellor>();
    var seen = new LinkedHashSet<String>();
    for (Map<?, ?> session : maps(context.get("sessions"))) {
      Object consultantId = session.get("consultantId");
      Long sessionId = number(session.get("id"));
      if (consultantId == null || sessionId == null) {
        continue;
      }
      if (token.sessionId() != null && !token.sessionId().equals(sessionId)) {
        continue;
      }
      if (seen.add(consultantId.toString())) {
        counsellors.add(new Counsellor(consultantId.toString(), sessionId));
      }
    }
    return counsellors;
  }

  private static String subjectId(HostToken token, String subject) {
    String effective = subject == null || subject.isBlank() ? (token.askerId() != null ? ASKER : SELF) : subject;
    if (SELF.equals(effective)) {
      return token.userId();
    }
    if (ASKER.equals(effective)) {
      if (token.askerId() == null || token.askerId().isBlank()) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Token has no client");
      }
      return token.askerId();
    }
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subject must be 'self' or 'asker'");
  }

  private Map<String, Object> read(String userId, long tenantId) {
    String key = tenantId + "|" + userId;
    Instant now = clock.instant();
    Cached cached = cache.get(key);
    if (cached != null && now.isBefore(cached.until())) {
      return cached.context();
    }
    var uri =
        UriComponentsBuilder.fromUri(properties.baseUrl())
            .path(properties.contextPath())
            .queryParam("userId", userId)
            .queryParam("tenantId", tenantId)
            .encode()
            .build()
            .toUri();
    Map<String, Object> context = core.get(uri, "Context of a user");
    cache.put(key, new Cached(context, now.plus(properties.contextCacheTtl())));
    return context;
  }

  /** Keeps only the sessions of one agency and the counsellors of those sessions. */
  static Map<String, Object> withinAgency(Map<String, Object> context, long agencyId) {
    var sessions =
        maps(context.get("sessions")).stream()
            .filter(session -> Objects.equals(number(session.get("agencyId")), agencyId))
            .toList();
    var counsellorIds =
        sessions.stream().map(session -> session.get("consultantId")).filter(Objects::nonNull).map(Object::toString).toList();
    var consultants =
        "consultant".equals(context.get("type"))
            ? maps(context.get("consultants"))
            : maps(context.get("consultants")).stream()
                .filter(consultant -> counsellorIds.contains(String.valueOf(consultant.get("id"))))
                .toList();
    List<?> agencyIds =
        context.get("agencyIds") instanceof List<?> ids
            ? ids.stream().filter(id -> Objects.equals(number(id), agencyId)).toList()
            : List.of();
    var filtered = new LinkedHashMap<>(context);
    filtered.put("agencyIds", agencyIds);
    filtered.put("sessions", sessions);
    filtered.put("consultants", consultants);
    return filtered;
  }

  private static List<Map<?, ?>> maps(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    return list.stream().filter(Map.class::isInstance).<Map<?, ?>>map(item -> (Map<?, ?>) item).toList();
  }

  private static Long number(Object value) {
    return value instanceof Number number ? number.longValue() : null;
  }

  public record Counsellor(String userId, long sessionId) {}

  private record Cached(Map<String, Object> context, Instant until) {}
}
