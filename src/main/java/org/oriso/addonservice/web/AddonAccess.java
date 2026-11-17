package org.oriso.addonservice.web;

import java.util.Set;
import org.oriso.addonservice.enablement.EnablementService;
import org.oriso.addonservice.host.ContextService;
import org.oriso.addonservice.registry.AddonRegistry;
import org.oriso.addonservice.registry.RegisteredAddon;
import org.oriso.addonservice.security.AgencyMembership;
import org.oriso.addonservice.security.Caller;
import org.oriso.addonservice.security.Role;
import org.oriso.addonservice.security.SessionAccess;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Decides whether a caller may use an add-on, optionally in the context of an agency or session. */
@Component
public class AddonAccess {

  /** Set by the web app when an add-on is used inside an agency context, e.g. a session. */
  public static final String AGENCY_HEADER = "X-Oriso-Agency-Id";

  /** Set by the web app when an add-on is used for one session (case view, client profile, chat menu). */
  public static final String SESSION_HEADER = "X-Oriso-Session-Id";

  private final AddonRegistry registry;
  private final EnablementService enablement;
  private final AgencyMembership membership;
  private final SessionAccess sessions;
  private final ContextService contexts;

  public AddonAccess(
      AddonRegistry registry,
      EnablementService enablement,
      AgencyMembership membership,
      SessionAccess sessions,
      ContextService contexts) {
    this.registry = registry;
    this.enablement = enablement;
    this.membership = membership;
    this.sessions = sessions;
    this.contexts = contexts;
  }

  public Granted require(String name, Caller caller, String agencyHeader, String sessionHeader) {
    RegisteredAddon addon =
        registry
            .find(name)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown add-on"));
    SessionContext session = sessionContext(caller, sessionHeader);
    Long agencyId =
        session != null && session.agencyId() != null ? session.agencyId() : agencyContext(caller, agencyHeader);
    if (!enablement.isEnabled(name, caller.tenantId(), agencyId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Add-on not enabled");
    }
    return new Granted(addon, agencyId, session);
  }

  /** Parses and verifies the agency header; null when the request has no agency context. */
  public Long agencyContext(Caller caller, String agencyHeader) {
    if (agencyHeader == null || agencyHeader.isBlank()) {
      return null;
    }
    long agencyId = parse(agencyHeader, AGENCY_HEADER);
    if (!membership.belongsTo(caller, agencyId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this agency");
    }
    return agencyId;
  }

  /**
   * Verifies the session header; null without one. A counsellor must be allowed to see the
   * session (UserService answers with its client); a client must own it, within their Träger.
   * Other roles carry no session context.
   */
  public SessionContext sessionContext(Caller caller, String sessionHeader) {
    if (sessionHeader == null || sessionHeader.isBlank()) {
      return null;
    }
    if (caller.hasRole(Role.CONSULTANT)) {
      long sessionId = parse(sessionHeader, SESSION_HEADER);
      SessionAccess.SessionInfo session =
          sessions
              .find(caller, sessionId)
              .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No access to this session"));
      return new SessionContext(sessionId, session.askerId(), session.agencyId());
    }
    if (caller.hasRole(Role.ASKER)) {
      long sessionId = parse(sessionHeader, SESSION_HEADER);
      if (caller.tenantId() == null) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No access to this session");
      }
      var own =
          contexts
              .ownSession(caller.userId(), caller.tenantId(), sessionId)
              .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No access to this session"));
      return new SessionContext(sessionId, null, own.agencyId());
    }
    return null;
  }

  /**
   * The agencies that decide what the registry lists: the session's agency for a counsellor's
   * session header, else the verified agency header, else all of the caller's own agencies.
   */
  public RegistryContext registryContext(Caller caller, String agencyHeader, String sessionHeader) {
    SessionContext session = sessionContext(caller, sessionHeader);
    if (session != null && session.agencyId() != null) {
      return new RegistryContext(Set.of(session.agencyId()), session.agencyId(), session);
    }
    Long agencyId = agencyContext(caller, agencyHeader);
    if (agencyId != null) {
      return new RegistryContext(Set.of(agencyId), agencyId, session);
    }
    return new RegistryContext(membership.requireAgenciesOf(caller), null, session);
  }

  /** Listed if enabled for at least one agency; without any agency the Träger switch decides. */
  public boolean isEnabled(String addon, Caller caller, RegistryContext context) {
    if (context.agencies().isEmpty()) {
      return enablement.isEnabled(addon, caller.tenantId(), null);
    }
    return context.agencies().stream()
        .anyMatch(agencyId -> enablement.isEnabled(addon, caller.tenantId(), agencyId));
  }

  private static long parse(String value, String header) {
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + header);
    }
  }

  public record SessionContext(long sessionId, String askerId, Long agencyId) {}

  /** {@code agencyId} is the single agency of the request, null when it lists the caller's own. */
  public record RegistryContext(Set<Long> agencies, Long agencyId, SessionContext session) {}

  public record Granted(RegisteredAddon addon, Long agencyId, SessionContext session) {}
}
