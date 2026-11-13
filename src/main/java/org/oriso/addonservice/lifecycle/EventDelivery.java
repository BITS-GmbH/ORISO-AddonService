package org.oriso.addonservice.lifecycle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One lifecycle event for one add-on, kept until the add-on accepted it or delivery was given up. */
@Entity
@Table(name = "addon_event_delivery")
public class EventDelivery {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "event_id", nullable = false, length = 64)
  private String eventId;

  @Column(name = "event_type", nullable = false, length = 64)
  private String eventType;

  @Column(name = "addon_name", nullable = false, length = 64)
  private String addonName;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
  private String payload;

  @Column(name = "attempts", nullable = false)
  private int attempts;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "delivered_at")
  private Instant deliveredAt;

  @Column(name = "failed_at")
  private Instant failedAt;

  @Column(name = "last_error", length = 255)
  private String lastError;

  protected EventDelivery() {}

  public EventDelivery(
      String eventId, String eventType, String addonName, Long tenantId, String payload, Instant now) {
    this.eventId = eventId;
    this.eventType = eventType;
    this.addonName = addonName;
    this.tenantId = tenantId;
    this.payload = payload;
    this.createdAt = now;
    this.nextAttemptAt = now;
  }

  public Long getId() {
    return id;
  }

  public String getEventId() {
    return eventId;
  }

  public String getEventType() {
    return eventType;
  }

  public String getAddonName() {
    return addonName;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public String getPayload() {
    return payload;
  }

  public int getAttempts() {
    return attempts;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  public Instant getDeliveredAt() {
    return deliveredAt;
  }

  public Instant getFailedAt() {
    return failedAt;
  }

  public String getLastError() {
    return lastError;
  }

  void delivered(Instant at) {
    attempts++;
    deliveredAt = at;
    lastError = null;
  }

  void retryAt(Instant at, String error) {
    attempts++;
    nextAttemptAt = at;
    lastError = truncate(error);
  }

  void giveUp(Instant at, String error) {
    attempts++;
    failedAt = at;
    lastError = truncate(error);
  }

  private static String truncate(String error) {
    return error == null || error.length() <= 255 ? error : error.substring(0, 255);
  }
}
