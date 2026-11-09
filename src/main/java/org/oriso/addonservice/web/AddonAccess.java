package org.oriso.addonservice.web;

import org.oriso.addonservice.enablement.EnablementService;
import org.oriso.addonservice.registry.AddonRegistry;
import org.oriso.addonservice.registry.RegisteredAddon;
import org.oriso.addonservice.security.AgencyMembership;
import org.oriso.addonservice.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Decides whether a caller may use an add-on, optionally in the context of one agency. */
@Component
public class AddonAccess {

  /** Set by the web app when an add-on is used inside an agency context, e.g. a session. */
  public static final String AGENCY_HEADER = "X-Oriso-Agency-Id";

  private final AddonRegistry registry;
  private final EnablementService enablement;
  private final AgencyMembership membership;

  public AddonAccess(AddonRegistry registry, EnablementService enablement, AgencyMembership membership) {
    this.registry = registry;
    this.enablement = enablement;
    this.membership = membership;
  }

  public Granted require(String name, Caller caller, String agencyHeader) {
    RegisteredAddon addon =
        registry
            .find(name)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown add-on"));
    Long agencyId = agencyContext(caller, agencyHeader);
    if (!enablement.isEnabled(name, caller.tenantId(), agencyId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Add-on not enabled");
    }
    return new Granted(addon, agencyId);
  }

  /** Parses and verifies the agency header; null when the request has no agency context. */
  public Long agencyContext(Caller caller, String agencyHeader) {
    if (agencyHeader == null || agencyHeader.isBlank()) {
      return null;
    }
    long agencyId;
    try {
      agencyId = Long.parseLong(agencyHeader.trim());
    } catch (NumberFormatException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + AGENCY_HEADER);
    }
    if (!membership.belongsTo(caller, agencyId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this agency");
    }
    return agencyId;
  }

  public record Granted(RegisteredAddon addon, Long agencyId) {}
}
