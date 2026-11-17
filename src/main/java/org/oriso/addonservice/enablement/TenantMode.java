package org.oriso.addonservice.enablement;

import java.util.Arrays;
import java.util.Optional;

/** How a SuperAdmin hands an add-on to a Träger. */
public enum TenantMode {
  /** Not available; the admins of the Träger do not see it. */
  OFF,
  /** The admins of the Träger switch it on or off themselves. */
  TENANT,
  /** Always on; the admins of the Träger see it but cannot switch it. */
  ON,
  /** Always on for the Träger and every agency; the admins of the Träger do not see it. */
  ON_HIDDEN;

  public boolean isVisibleToTenantAdmins() {
    return this == TENANT || this == ON;
  }

  public static Optional<TenantMode> parse(String value) {
    return Arrays.stream(values()).filter(mode -> mode.name().equals(value)).findFirst();
  }
}
