package org.oriso.addonservice.enablement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One switch: an add-on for a Träger ({@code agencyId == null}) or an override for one agency
 * of that Träger.
 */
@Entity
@Table(name = "addon_enablement")
public class AddonEnablement {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "addon_name", nullable = false, length = 64)
  private String addonName;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(name = "agency_id")
  private Long agencyId;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Enumerated(EnumType.STRING)
  @Column(name = "mode", length = 16)
  private TenantMode mode;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by", length = 64)
  private String updatedBy;

  protected AddonEnablement() {}

  public AddonEnablement(String addonName, Long tenantId, Long agencyId) {
    this.addonName = addonName;
    this.tenantId = tenantId;
    this.agencyId = agencyId;
  }

  public Long getId() {
    return id;
  }

  public String getAddonName() {
    return addonName;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public Long getAgencyId() {
    return agencyId;
  }

  public boolean isEnabled() {
    return enabled;
  }

  /**
   * The mode of a Träger row; agency rows have none. A Träger row without one (written before
   * modes existed) counts as ON or OFF by its flag.
   */
  public TenantMode getMode() {
    if (agencyId != null) {
      return null;
    }
    if (mode == null) {
      return enabled ? TenantMode.ON : TenantMode.OFF;
    }
    return mode;
  }

  /** Whether the add-on is on at the level of the Träger, before agency overrides. */
  public boolean isOnForTenant() {
    return switch (getMode()) {
      case OFF -> false;
      case ON, ON_HIDDEN -> true;
      case TENANT -> enabled;
    };
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

  void set(TenantMode mode, boolean enabled, Instant at, String by) {
    this.mode = mode;
    this.enabled = enabled;
    this.updatedAt = at;
    this.updatedBy = by;
  }
}
