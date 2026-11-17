package org.oriso.addonservice.web;

import org.oriso.addonservice.enablement.EnablementService;
import org.oriso.addonservice.registry.AddonRegistry;
import org.oriso.addonservice.registry.RegisteredAddon;
import org.oriso.addonservice.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Which add-ons an admin may manage for a Träger: SuperAdmins every add-on, the admins of the
 * Träger only those on globally and handed to the Träger in mode TENANT or ON. The others do not
 * exist for them (404).
 */
@Component
public class AdminAddonAccess {

  private final AddonRegistry registry;
  private final EnablementService enablement;

  public AdminAddonAccess(AddonRegistry registry, EnablementService enablement) {
    this.registry = registry;
    this.enablement = enablement;
  }

  public RegisteredAddon require(Caller caller, String name, long tenantId) {
    requireTenantAdmin(caller, tenantId);
    RegisteredAddon addon = requireKnown(name);
    if (!caller.isSuperAdmin() && !enablement.isVisibleToTenantAdmins(name, tenantId)) {
      throw unknown();
    }
    return addon;
  }

  public boolean isVisible(Caller caller, String name, long tenantId) {
    return caller.isSuperAdmin() || enablement.isVisibleToTenantAdmins(name, tenantId);
  }

  public RegisteredAddon requireKnown(String name) {
    return registry.find(name).orElseThrow(AdminAddonAccess::unknown);
  }

  public static void requireTenantAdmin(Caller caller, long tenantId) {
    if (!caller.isAdminOfTenant(tenantId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not an admin of this Träger");
    }
  }

  public static void requireSuperAdmin(Caller caller, String reason) {
    if (!caller.isSuperAdmin()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
    }
  }

  private static ResponseStatusException unknown() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown add-on");
  }
}
