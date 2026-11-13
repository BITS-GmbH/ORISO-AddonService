package org.oriso.addonservice.enablement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** The platform-wide switch of one add-on; without a row the add-on is off everywhere. */
@Entity
@Table(name = "addon_global_switch")
public class AddonGlobalSwitch {

  @Id
  @Column(name = "addon_name", nullable = false, length = 64)
  private String addonName;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by", length = 64)
  private String updatedBy;

  protected AddonGlobalSwitch() {}

  public AddonGlobalSwitch(String addonName) {
    this.addonName = addonName;
  }

  public String getAddonName() {
    return addonName;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  void set(boolean enabled, Instant at, String by) {
    this.enabled = enabled;
    this.updatedAt = at;
    this.updatedBy = by;
  }
}
