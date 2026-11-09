package org.oriso.addonservice.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Settings of an add-on for a Träger ({@code agencyId == null}) or one of its agencies, as JSON. */
@Entity
@Table(name = "addon_setting")
public class AddonSetting {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "addon_name", nullable = false, length = 64)
  private String addonName;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(name = "agency_id")
  private Long agencyId;

  @Column(name = "values_json", nullable = false, columnDefinition = "TEXT")
  private String valuesJson;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by", length = 64)
  private String updatedBy;

  protected AddonSetting() {}

  public AddonSetting(String addonName, Long tenantId, Long agencyId) {
    this.addonName = addonName;
    this.tenantId = tenantId;
    this.agencyId = agencyId;
  }

  public String getValuesJson() {
    return valuesJson;
  }

  void set(String valuesJson, Instant at, String by) {
    this.valuesJson = valuesJson;
    this.updatedAt = at;
    this.updatedBy = by;
  }
}
