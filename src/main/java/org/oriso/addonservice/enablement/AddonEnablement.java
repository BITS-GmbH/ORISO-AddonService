package org.oriso.addonservice.enablement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
