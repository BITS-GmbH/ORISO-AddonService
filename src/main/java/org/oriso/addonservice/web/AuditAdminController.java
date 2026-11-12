package org.oriso.addonservice.web;

import java.time.Instant;
import java.util.Set;
import org.oriso.addonservice.audit.AuditService;
import org.oriso.addonservice.security.AgencyMembership;
import org.oriso.addonservice.security.Caller;
import org.oriso.addonservice.security.Role;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Audit entries of add-ons for ORISO-Admin. SuperAdmins see every Träger, Träger admins their
 * own, agency admins only the entries written in their agencies.
 */
@RestController
public class AuditAdminController {

  private static final int MAX_PAGE_SIZE = 200;

  private final AuditService audit;
  private final AgencyMembership membership;

  public AuditAdminController(AuditService audit, AgencyMembership membership) {
    this.audit = audit;
    this.membership = membership;
  }

  @GetMapping("/addons/admin/audit")
  public AuditService.Page list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) Long tenantId,
      @RequestParam(required = false) String addon,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
    }
    Caller caller = Caller.from(jwt);
    Scope scope = scopeOf(caller, tenantId);
    String addonFilter = addon == null || addon.isBlank() ? null : addon;
    return audit.search(new AuditService.Query(scope.tenantId, scope.agencies, addonFilter, from, to, page, size));
  }

  private Scope scopeOf(Caller caller, Long requestedTenant) {
    if (caller.isSuperAdmin()) {
      return new Scope(requestedTenant, null);
    }
    if (caller.tenantId() == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No Träger in token");
    }
    if (requestedTenant != null && !requestedTenant.equals(caller.tenantId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not an admin of this Träger");
    }
    if (caller.isAdminOfTenant(caller.tenantId())) {
      return new Scope(caller.tenantId(), null);
    }
    if (caller.hasRole(Role.AGENCY_ADMIN) || caller.hasRole(Role.RESTRICTED_AGENCY_ADMIN)) {
      return new Scope(caller.tenantId(), membership.agenciesOf(caller));
    }
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not an admin");
  }

  private record Scope(Long tenantId, Set<Long> agencies) {}
}
