package org.oriso.addonservice.calendar;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.oriso.addonservice.config.AddonProperties;
import org.oriso.addonservice.enablement.EnablementService;
import org.oriso.addonservice.host.LocalizedText;
import org.oriso.addonservice.manifest.AddonManifest;
import org.oriso.addonservice.manifest.Slot;
import org.oriso.addonservice.registry.AddonRegistry;
import org.oriso.addonservice.registry.RegisteredAddon;
import org.oriso.addonservice.security.Caller;
import org.oriso.addonservice.token.AddonTokenIssuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Collects the calendar entries of all add-ons the caller can use. Each add-on is asked in
 * parallel with a token for the caller; add-ons that fail or are too slow are reported, not fatal.
 */
@Service
public class CalendarService {

  private static final Logger log = LoggerFactory.getLogger(CalendarService.class);
  private static final Set<String> KINDS = Set.of("appointment", "deadline", "reminder");
  private static final int MAX_ID_LENGTH = 128;
  private static final int MAX_TITLE_LENGTH = 200;

  private final AddonRegistry registry;
  private final EnablementService enablement;
  private final AddonTokenIssuer tokens;
  private final AddonProperties.Calendar properties;
  private final RestClient client;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public CalendarService(
      AddonRegistry registry, EnablementService enablement, AddonTokenIssuer tokens, AddonProperties properties) {
    this.registry = registry;
    this.enablement = enablement;
    this.tokens = tokens;
    this.properties = properties.calendar();
    var requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(this.properties.timeout()).build());
    requestFactory.setReadTimeout(this.properties.timeout());
    this.client = RestClient.builder().requestFactory(requestFactory).build();
  }

  public Calendar collect(Caller caller, Long agencyId, Instant from, Instant to) {
    List<RegisteredAddon> addons =
        registry.all().stream()
            .filter(addon -> addon.manifest().calendar() != null)
            .filter(addon -> enablement.isEnabled(addon.name(), caller.tenantId(), agencyId))
            .filter(addon -> !visibleSlots(addon.manifest(), caller).isEmpty())
            .toList();
    var requests =
        addons.stream()
            .map(
                addon ->
                    new Request(
                        addon,
                        CompletableFuture.supplyAsync(() -> fetch(addon, caller, agencyId, from, to), executor)
                            .orTimeout(properties.timeout().toMillis(), TimeUnit.MILLISECONDS)))
            .toList();
    var entries = new ArrayList<CalendarEntry>();
    var errors = new ArrayList<String>();
    for (Request request : requests) {
      try {
        entries.addAll(request.entries.join());
      } catch (RuntimeException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        log.warn("Calendar of {} not available: {}", request.addon.name(), cause.toString());
        errors.add(request.addon.name());
      }
    }
    entries.sort(
        Comparator.comparing(CalendarEntry::start)
            .thenComparing(CalendarEntry::addon)
            .thenComparing(CalendarEntry::id));
    return new Calendar(entries, errors);
  }

  private List<CalendarEntry> fetch(
      RegisteredAddon addon, Caller caller, Long agencyId, Instant from, Instant to) {
    URI uri =
        UriComponentsBuilder.fromUri(addon.baseUrl().resolve(addon.manifest().calendar()))
            .queryParam("from", from.toString())
            .queryParam("to", to.toString())
            .build()
            .toUri();
    Map<?, ?> body =
        client
            .get()
            .uri(uri)
            .header("Authorization", "Bearer " + tokens.issue(addon.name(), caller, agencyId))
            .retrieve()
            .body(Map.class);
    if (body == null || !(body.get("entries") instanceof List<?> raw)) {
      throw new IllegalStateException("answer has no entries list");
    }
    if (raw.size() > properties.maxEntriesPerAddon()) {
      log.warn(
          "Calendar of {} returned {} entries, keeping the first {}",
          addon.name(),
          raw.size(),
          properties.maxEntriesPerAddon());
    }
    var visible = visibleSlots(addon.manifest(), caller);
    var entries = new ArrayList<CalendarEntry>();
    int dropped = 0;
    for (Object item : raw.subList(0, Math.min(raw.size(), properties.maxEntriesPerAddon()))) {
      var entry = entry(addon, item, visible, from, to);
      if (entry.isPresent()) {
        entries.add(entry.get());
      } else {
        dropped++;
      }
    }
    if (dropped > 0) {
      log.info("Dropped {} invalid or out-of-range calendar entries of {}", dropped, addon.name());
    }
    return entries;
  }

  private static Optional<CalendarEntry> entry(
      RegisteredAddon addon, Object item, Set<String> visibleSlots, Instant from, Instant to) {
    if (!(item instanceof Map<?, ?> raw)) {
      return Optional.empty();
    }
    String id = raw.get("id") instanceof String text ? text : raw.get("id") instanceof Number n ? n.toString() : null;
    String appId = raw.get("appId") instanceof String text ? text : null;
    String kind = raw.get("kind") instanceof String text ? text : null;
    Map<String, String> title = text(raw.get("title"));
    Instant start = instant(raw.get("start"));
    Instant end = raw.get("end") == null ? null : instant(raw.get("end"));
    Boolean allDay = raw.get("allDay") instanceof Boolean flag ? flag : null;
    Link link = link(raw.get("link"));

    boolean valid =
        id != null
            && !id.isBlank()
            && id.length() <= MAX_ID_LENGTH
            && appId != null
            && addon.app(appId).isPresent()
            && KINDS.contains(kind)
            && title != null
            && LocalizedText.problems("title", title, MAX_TITLE_LENGTH).isEmpty()
            && start != null
            && (raw.get("end") == null || (end != null && !end.isBefore(start)))
            && (raw.get("allDay") == null || allDay != null)
            && link != null
            && addon.app(appId).get().slots().contains(link.slot())
            && visibleSlots.contains(link.slot());
    if (!valid) {
      return Optional.empty();
    }
    Instant last = end == null ? start : end;
    if (!start.isBefore(to) || last.isBefore(from)) {
      return Optional.empty();
    }
    return Optional.of(new CalendarEntry(addon.name(), id, appId, kind, title, start, end, allDay, link));
  }

  private static Set<String> visibleSlots(AddonManifest manifest, Caller caller) {
    return manifest.apps().stream()
        .map(AddonManifest.App::slots)
        .flatMap(Collection::stream)
        .filter(slot -> Slot.fromId(slot).map(s -> s.isVisibleTo(caller)).orElse(false))
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Map<String, String> text(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      return null;
    }
    for (var e : map.entrySet()) {
      if (!(e.getKey() instanceof String) || !(e.getValue() instanceof String)) {
        return null;
      }
    }
    @SuppressWarnings("unchecked")
    Map<String, String> text = (Map<String, String>) map;
    return Map.copyOf(text);
  }

  private static Instant instant(Object value) {
    if (!(value instanceof String text)) {
      return null;
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static Link link(Object value) {
    if (!(value instanceof Map<?, ?> map) || !(map.get("slot") instanceof String slot)) {
      return null;
    }
    Object sessionId = map.get("sessionId");
    if (sessionId != null && !(sessionId instanceof Number)) {
      return null;
    }
    return new Link(slot, sessionId == null ? null : ((Number) sessionId).longValue());
  }

  @PreDestroy
  void close() {
    executor.shutdownNow();
  }

  private record Request(RegisteredAddon addon, CompletableFuture<List<CalendarEntry>> entries) {}

  public record Calendar(List<CalendarEntry> entries, List<String> errors) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CalendarEntry(
      String addon,
      String id,
      String appId,
      String kind,
      Map<String, String> title,
      Instant start,
      Instant end,
      Boolean allDay,
      Link link) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Link(String slot, Long sessionId) {}
}
