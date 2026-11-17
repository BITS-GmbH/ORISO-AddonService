package org.oriso.addonservice.manifest;

import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.oriso.addonservice.security.Caller;
import org.oriso.addonservice.security.Role;

/**
 * The places in ORISO where add-on UI can appear, and who sees each of them. Session slots are
 * opened for one verified session. Anonymous slots are shown to visitors without an account and
 * never listed in the registry for logged-in users.
 */
public enum Slot {
  ASKER_NAVIGATION("asker.navigation", Set.of(Role.ASKER), Context.NONE),
  ASKER_SESSION_MENU("asker.session-menu", Set.of(Role.ASKER), Context.SESSION),
  CONSULTANT_NAVIGATION("consultant.navigation", Set.of(Role.CONSULTANT), Context.NONE),
  CONSULTANT_SESSION("consultant.session", Set.of(Role.CONSULTANT), Context.SESSION),
  CONSULTANT_SESSION_MENU("consultant.session-menu", Set.of(Role.CONSULTANT), Context.SESSION),
  CONSULTANT_ASKER_PROFILE("consultant.asker-profile", Set.of(Role.CONSULTANT), Context.SESSION),
  ADMIN_PAGE(
      "admin.page",
      Set.of(
          Role.TENANT_ADMIN,
          Role.SINGLE_TENANT_ADMIN,
          Role.AGENCY_ADMIN,
          Role.RESTRICTED_AGENCY_ADMIN),
      Context.NONE),
  REGISTRATION_AGENCY_FINDER("registration.agency-finder", Set.of(), Context.ANONYMOUS);

  private enum Context {
    NONE,
    SESSION,
    ANONYMOUS
  }

  private final String id;
  private final Set<String> audience;
  private final Context context;

  Slot(String id, Set<String> audience, Context context) {
    this.id = id;
    this.audience = audience;
    this.context = context;
  }

  public String id() {
    return id;
  }

  public boolean requiresSession() {
    return context == Context.SESSION;
  }

  public boolean isAnonymous() {
    return context == Context.ANONYMOUS;
  }

  public boolean isVisibleTo(Caller caller) {
    return isVisibleTo(caller.roles());
  }

  public boolean isVisibleTo(Collection<String> roles) {
    return audience.stream().anyMatch(roles::contains);
  }

  public static Optional<Slot> fromId(String id) {
    return Arrays.stream(values()).filter(slot -> slot.id.equals(id)).findFirst();
  }
}
