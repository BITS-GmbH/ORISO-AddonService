package org.oriso.addonservice.web;

import java.util.List;
import java.util.Map;
import org.oriso.addonservice.audit.AuditService;
import org.oriso.addonservice.host.ContextService;
import org.oriso.addonservice.host.GateService;
import org.oriso.addonservice.host.HostToken;
import org.oriso.addonservice.host.NotificationService;
import org.oriso.addonservice.registry.AddonRegistry;
import org.oriso.addonservice.registry.RegisteredAddon;
import org.oriso.addonservice.settings.SettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Host API for add-on backends. They authenticate with the token the AddonService gave them, so
 * every answer is scoped to the add-on, Träger and agency of that token.
 */
@RestController
public class HostController {

  private final AddonRegistry registry;
  private final SettingsService settings;
  private final NotificationService notifications;
  private final AuditService audit;
  private final GateService gates;
  private final ContextService context;

  public HostController(
      AddonRegistry registry,
      SettingsService settings,
      NotificationService notifications,
      AuditService audit,
      GateService gates,
      ContextService context) {
    this.registry = registry;
    this.settings = settings;
    this.notifications = notifications;
    this.audit = audit;
    this.gates = gates;
    this.context = context;
  }

  /** Core gates for the Träger of the token, e.g. whether its DPA allows new counselling. */
  @GetMapping("/addons/host/gates")
  public Map<String, Object> gates(@AuthenticationPrincipal Jwt jwt) {
    HostToken token = HostToken.from(jwt);
    addonOf(token);
    return gates.gates(token.requireTenant());
  }

  /** Sessions, agencies and counsellors of the token's user or client, within its Träger (and agency). */
  @GetMapping("/addons/host/context")
  public Map<String, Object> context(
      @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String subject) {
    HostToken token = HostToken.from(jwt);
    addonOf(token);
    return context.context(token, subject);
  }

  @GetMapping("/addons/host/settings")
  public Map<String, Object> settings(@AuthenticationPrincipal Jwt jwt) {
    HostToken token = HostToken.from(jwt);
    return settings.effective(addonOf(token), token.requireTenant(), token.agencyId());
  }

  @PostMapping("/addons/host/notifications")
  public ResponseEntity<Object> notify(
      @AuthenticationPrincipal Jwt jwt, @RequestBody NotificationService.NotificationRequest request) {
    HostToken token = HostToken.from(jwt);
    Object result = notifications.send(addonOf(token), token, request);
    return ResponseEntity.accepted()
        .body(result instanceof List<?> ids ? Map.of("ids", ids) : new Created(result));
  }

  @PostMapping("/addons/host/audit")
  public ResponseEntity<Created> audit(
      @AuthenticationPrincipal Jwt jwt, @RequestBody AuditService.AuditRequest request) {
    HostToken token = HostToken.from(jwt);
    long id = audit.record(addonOf(token), token, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(new Created(id));
  }

  private RegisteredAddon addonOf(HostToken token) {
    return registry
        .find(token.addon())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown add-on"));
  }

  public record Created(Object id) {}
}
