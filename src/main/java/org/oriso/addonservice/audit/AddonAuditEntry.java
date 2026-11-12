package org.oriso.addonservice.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/** One audit entry an add-on wrote through the host API. Entries are never changed. */
@Entity
@Immutable
@Table(name = "addon_audit_entry")
public class AddonAuditEntry {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "addon_name", nullable = false, updatable = false, length = 64)
  private String addonName;

  @Column(name = "app_id", updatable = false, length = 64)
  private String appId;

  @Column(name = "action", nullable = false, updatable = false, length = 64)
  private String action;

  @Column(name = "summary_json", nullable = false, updatable = false, columnDefinition = "TEXT")
  private String summaryJson;

  @Column(name = "actor_id", updatable = false, length = 64)
  private String actorId;

  @Column(name = "actor_username", updatable = false)
  private String actorUsername;

  @Column(name = "actor_roles_json", updatable = false, columnDefinition = "TEXT")
  private String actorRolesJson;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private Long tenantId;

  @Column(name = "agency_id", updatable = false)
  private Long agencyId;

  @Column(name = "session_id", updatable = false)
  private Long sessionId;

  @Column(name = "subject_id", updatable = false, length = 64)
  private String subjectId;

  protected AddonAuditEntry() {}

  AddonAuditEntry(
      Instant createdAt,
      String addonName,
      String appId,
      String action,
      String summaryJson,
      String actorId,
      String actorUsername,
      String actorRolesJson,
      Long tenantId,
      Long agencyId,
      Long sessionId,
      String subjectId) {
    this.createdAt = createdAt;
    this.addonName = addonName;
    this.appId = appId;
    this.action = action;
    this.summaryJson = summaryJson;
    this.actorId = actorId;
    this.actorUsername = actorUsername;
    this.actorRolesJson = actorRolesJson;
    this.tenantId = tenantId;
    this.agencyId = agencyId;
    this.sessionId = sessionId;
    this.subjectId = subjectId;
  }

  public Long getId() {
    return id;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getAddonName() {
    return addonName;
  }

  public String getAppId() {
    return appId;
  }

  public String getAction() {
    return action;
  }

  public String getSummaryJson() {
    return summaryJson;
  }

  public String getActorId() {
    return actorId;
  }

  public String getActorUsername() {
    return actorUsername;
  }

  public String getActorRolesJson() {
    return actorRolesJson;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public Long getAgencyId() {
    return agencyId;
  }

  public Long getSessionId() {
    return sessionId;
  }

  public String getSubjectId() {
    return subjectId;
  }
}
