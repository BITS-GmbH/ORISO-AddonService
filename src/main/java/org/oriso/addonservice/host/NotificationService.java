package org.oriso.addonservice.host;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.oriso.addonservice.config.AddonProperties;
import org.oriso.addonservice.manifest.AddonManifest;
import org.oriso.addonservice.manifest.Slot;
import org.oriso.addonservice.registry.RegisteredAddon;
import org.oriso.addonservice.security.Role;
import org.oriso.addonservice.token.TechnicalClientToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Notifications from add-ons. An add-on can only address the user of its token or, for a
 * counsellor in a session, that session's client; UserService decides how they are delivered.
 */
@Service
public class NotificationService {

  public static final String SELF = "self";
  public static final String ASKER = "asker";

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Set<String> SESSION_SLOTS =
      Set.of(Slot.CONSULTANT_SESSION.id(), Slot.CONSULTANT_ASKER_PROFILE.id());

  private final RestClient userService;
  private final String notificationsPath;
  private final TechnicalClientToken technicalToken;

  public NotificationService(AddonProperties properties, TechnicalClientToken technicalToken) {
    var requestFactory =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    requestFactory.setReadTimeout(TIMEOUT);
    this.userService =
        RestClient.builder()
            .requestFactory(requestFactory)
            .baseUrl(properties.userService().baseUrl().toString())
            .build();
    this.notificationsPath = properties.userService().notificationsPath();
    this.technicalToken = technicalToken;
  }

  /** Validates the notification, sends it to UserService and returns UserService's id. */
  public Object send(RegisteredAddon addon, HostToken token, NotificationRequest request) {
    long tenantId = token.requireTenant();
    var problems = new ArrayList<String>();
    if (!SELF.equals(request.recipient()) && !ASKER.equals(request.recipient())) {
      problems.add("recipient must be 'self' or 'asker'");
    }
    problems.addAll(LocalizedText.problems("title", request.title(), 120));
    problems.addAll(LocalizedText.problems("text", request.text(), 500));
    if (request.appId() == null || request.appId().isBlank()) {
      problems.add("appId is required");
    }
    String slot = request.target() == null ? null : request.target().slot();
    if (slot == null || slot.isBlank()) {
      problems.add("target.slot is required");
    }
    if (!problems.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", problems));
    }

    AddonManifest.App app = requireApp(addon, request.appId());
    if (!app.slots().contains(slot)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "App '" + app.id() + "' has no slot '" + slot + "'");
    }
    String recipientId = recipient(token, request.recipient());
    List<String> recipientRoles = ASKER.equals(request.recipient()) ? List.of(Role.ASKER) : token.roles();
    if (!Slot.fromId(slot).map(s -> s.isVisibleTo(recipientRoles)).orElse(false)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The recipient cannot open slot '" + slot + "'");
    }
    Long sessionId = null;
    if (SESSION_SLOTS.contains(slot)) {
      if (token.sessionId() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot '" + slot + "' needs a session in the token");
      }
      sessionId = token.sessionId();
    }

    var payload = new LinkedHashMap<String, Object>();
    payload.put("userId", recipientId);
    payload.put("tenantId", tenantId);
    payload.put("addon", addon.name());
    payload.put("appId", app.id());
    payload.put("slot", slot);
    payload.put("sessionId", sessionId);
    payload.put("important", Boolean.TRUE.equals(request.important()));
    payload.put("title", request.title());
    payload.put("text", request.text());
    return deliver(addon.name(), payload);
  }

  public static AddonManifest.App requireApp(RegisteredAddon addon, String appId) {
    return addon
        .app(appId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown app '" + appId + "'"));
  }

  private static String recipient(HostToken token, String recipient) {
    if (SELF.equals(recipient)) {
      return token.userId();
    }
    if (token.askerId() == null || token.askerId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Token has no client to notify");
    }
    return token.askerId();
  }

  private Object deliver(String addon, Map<String, Object> payload) {
    try {
      Map<?, ?> response =
          userService
              .post()
              .uri(notificationsPath)
              .header("Authorization", "Bearer " + technicalToken.get())
              .contentType(MediaType.APPLICATION_JSON)
              .body(payload)
              .retrieve()
              .body(Map.class);
      if (response == null || response.get("id") == null) {
        log.warn("UserService accepted a notification of {} without returning an id", addon);
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "UserService returned no id");
      }
      return response.get("id");
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401) {
        technicalToken.invalidate();
      }
      log.warn("UserService answered {} for a notification of {}", e.getStatusCode().value(), addon);
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "UserService refused the notification");
    } catch (RestClientException | IllegalStateException e) {
      log.warn("Could not send a notification of {} to UserService: {}", addon, e.getMessage());
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "UserService not reachable");
    }
  }

  public record NotificationRequest(
      String recipient,
      String appId,
      Boolean important,
      Map<String, String> title,
      Map<String, String> text,
      Target target) {}

  public record Target(String slot) {}
}
