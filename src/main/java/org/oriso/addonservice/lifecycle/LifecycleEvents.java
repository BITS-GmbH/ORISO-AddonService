package org.oriso.addonservice.lifecycle;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.oriso.addonservice.registry.AddonRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Takes a lifecycle event of the core (from RabbitMQ) and queues one delivery per add-on that
 * lists the event type in its manifest's {@code events}. Duplicates of an event (same id) are
 * queued once, so a redelivered message does no harm.
 */
@Service
public class LifecycleEvents {

  public static final String USER_REGISTERED = "user.registered";
  public static final String SESSION_INTERACTION = "session.interaction";

  /** The event types of contract oriso-addon/v1; others are ignored. */
  public static final Set<String> TYPES =
      Set.of("user.deleted", "session.deleted", USER_REGISTERED, SESSION_INTERACTION);

  public static final Set<String> INTERACTIONS = Set.of("message", "call", "video_call", "appointment");

  /**
   * Fields passed on for the event types whose payload the AddonService narrows down, so that
   * nothing personal beyond the contract reaches an add-on even if the core sends more.
   */
  private static final Map<String, List<String>> FIELDS =
      Map.of(
          USER_REGISTERED, List.of("userId", "registrationRef"),
          SESSION_INTERACTION, List.of("sessionId", "agencyId", "consultantId", "interaction"));

  private static final int MAX_REGISTRATION_REF = 128;

  private static final List<String> ENVELOPE = List.of("id", "type", "occurredAt", "tenantId");

  private static final Logger log = LoggerFactory.getLogger(LifecycleEvents.class);

  private final AddonRegistry registry;
  private final EventDeliveryRepository deliveries;
  private final JsonMapper json;
  private final Clock clock;

  public LifecycleEvents(
      AddonRegistry registry, EventDeliveryRepository deliveries, JsonMapper json, Clock clock) {
    this.registry = registry;
    this.deliveries = deliveries;
    this.json = json;
    this.clock = clock;
  }

  /** Returns how many deliveries were queued; malformed or unknown events are logged and dropped. */
  @Transactional
  public int accept(String message) {
    JsonNode event;
    try {
      event = json.readTree(message);
    } catch (RuntimeException e) {
      log.warn("Dropped a lifecycle message that is not JSON");
      return 0;
    }
    String id = text(event, "id");
    String type = text(event, "type");
    if (id == null || type == null || !TYPES.contains(type)) {
      log.warn("Dropped a lifecycle event with id {} and type {}", id, type);
      return 0;
    }
    String problem = problem(type, event);
    if (problem != null) {
      log.warn("Dropped lifecycle event {} ({}): {}", id, type, problem);
      return 0;
    }
    String payload = FIELDS.containsKey(type) ? narrowed(event, FIELDS.get(type)) : message;
    Long tenantId = event.hasNonNull("tenantId") && event.get("tenantId").canConvertToLong() ? event.get("tenantId").asLong() : null;
    int queued = 0;
    for (var addon : registry.all()) {
      if (!addon.manifest().events().contains(type) || deliveries.existsByEventIdAndAddonName(id, addon.name())) {
        continue;
      }
      try {
        deliveries.save(new EventDelivery(id, type, addon.name(), tenantId, payload, clock.instant()));
        queued++;
      } catch (DataIntegrityViolationException e) {
        // The same event queued concurrently; the unique key keeps it once.
      }
    }
    log.info("Lifecycle event {} ({}) queued for {} add-on(s)", id, type, queued);
    return queued;
  }

  private static String problem(String type, JsonNode event) {
    if (USER_REGISTERED.equals(type)) {
      if (text(event, "userId") == null) {
        return "userId missing";
      }
      String ref = text(event, "registrationRef");
      if (ref != null && ref.length() > MAX_REGISTRATION_REF) {
        return "registrationRef longer than " + MAX_REGISTRATION_REF;
      }
    }
    if (SESSION_INTERACTION.equals(type)) {
      if (!event.hasNonNull("sessionId") || !event.get("sessionId").canConvertToLong()) {
        return "sessionId missing";
      }
      if (!INTERACTIONS.contains(text(event, "interaction"))) {
        return "interaction must be one of " + INTERACTIONS;
      }
    }
    return null;
  }

  private String narrowed(JsonNode event, List<String> fields) {
    ObjectNode payload = json.createObjectNode();
    for (String field : ENVELOPE) {
      if (event.has(field)) {
        payload.set(field, event.get(field));
      }
    }
    for (String field : fields) {
      if (event.has(field)) {
        payload.set(field, event.get(field));
      }
    }
    return json.writeValueAsString(payload);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isString() && !value.asString().isBlank() ? value.asString() : null;
  }
}
