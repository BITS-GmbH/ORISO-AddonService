package org.oriso.addonservice.manifest;

import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.oriso.addonservice.security.Caller;
import org.oriso.addonservice.security.Role;

/** The places in ORISO where add-on UI can appear, and who sees each of them. */
public enum Slot {
  ASKER_NAVIGATION("asker.navigation", Set.of(Role.ASKER)),
  CONSULTANT_NAVIGATION("consultant.navigation", Set.of(Role.CONSULTANT)),
  CONSULTANT_SESSION("consultant.session", Set.of(Role.CONSULTANT)),
  CONSULTANT_ASKER_PROFILE("consultant.asker-profile", Set.of(Role.CONSULTANT)),
  ADMIN_PAGE(
      "admin.page",
      Set.of(
          Role.TENANT_ADMIN,
          Role.SINGLE_TENANT_ADMIN,
          Role.AGENCY_ADMIN,
          Role.RESTRICTED_AGENCY_ADMIN));

  private final String id;
  private final Set<String> audience;

  Slot(String id, Set<String> audience) {
    this.id = id;
    this.audience = audience;
  }

  public String id() {
    return id;
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
