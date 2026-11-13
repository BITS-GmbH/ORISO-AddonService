package org.oriso.addonservice.lifecycle;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.oriso.addonservice.config.AddonProperties;
import org.oriso.addonservice.registry.AddonRegistry;
import org.oriso.addonservice.registry.RegisteredAddon;
import org.oriso.addonservice.token.AddonTokenIssuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Delivers queued lifecycle events: {@code POST <add-on><addonPath>} with the event as body and a
 * service token (role {@code addon-service}, the event's Träger). A 2xx answer means the add-on
 * has handled it; anything else is retried with growing pauses until {@code giveUpAfter}, then
 * logged as an error. Add-on handlers must be idempotent and accept unknown ids.
 */
@Component
public class EventDispatcher {

  private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

  private final AddonProperties.Lifecycle properties;
  private final EventDeliveryRepository deliveries;
  private final AddonRegistry registry;
  private final AddonTokenIssuer tokens;
  private final Clock clock;
  private final RestClient http;

  public EventDispatcher(
      AddonProperties properties,
      EventDeliveryRepository deliveries,
      AddonRegistry registry,
      AddonTokenIssuer tokens,
      Clock clock) {
    this.properties = properties.lifecycle();
    this.deliveries = deliveries;
    this.registry = registry;
    this.tokens = tokens;
    this.clock = clock;
    var requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(this.properties.timeout()).build());
    requestFactory.setReadTimeout(this.properties.timeout());
    this.http = RestClient.builder().requestFactory(requestFactory).build();
  }

  @Scheduled(
      initialDelayString = "${addons.lifecycle.dispatch-initial-delay-ms:20000}",
      fixedDelayString = "${addons.lifecycle.dispatch-interval-ms:15000}")
  public void dispatchDue() {
    try {
      dispatch();
    } catch (RuntimeException e) {
      log.warn("Lifecycle dispatch failed: {}", e.getMessage());
    }
  }

  /** Delivers what is due now; returns how many deliveries were attempted. */
  public int dispatch() {
    var due = deliveries.findDue(clock.instant(), PageRequest.of(0, properties.batchSize()));
    for (EventDelivery delivery : due) {
      attempt(delivery);
      deliveries.save(delivery);
    }
    return due.size();
  }

  private void attempt(EventDelivery delivery) {
    Instant now = clock.instant();
    var addon = registry.find(delivery.getAddonName());
    if (addon.isEmpty()) {
      retryOrGiveUp(delivery, now, "add-on not discovered");
      return;
    }
    try {
      http.post()
          .uri(target(addon.get()))
          .header("Authorization", "Bearer " + tokens.issueForService(delivery.getAddonName(), tenant(delivery)))
          .contentType(MediaType.APPLICATION_JSON)
          .body(delivery.getPayload())
          .retrieve()
          .toBodilessEntity();
      delivery.delivered(now);
      log.info(
          "Lifecycle event {} ({}) delivered to {}", delivery.getEventId(), delivery.getEventType(), delivery.getAddonName());
    } catch (RestClientResponseException e) {
      retryOrGiveUp(delivery, now, "HTTP " + e.getStatusCode().value());
    } catch (RestClientException e) {
      retryOrGiveUp(delivery, now, e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private void retryOrGiveUp(EventDelivery delivery, Instant now, String error) {
    if (!now.isBefore(delivery.getCreatedAt().plus(properties.giveUpAfter()))) {
      delivery.giveUp(now, error);
      log.error(
          "Gave up delivering lifecycle event {} ({}) to {} after {} attempts: {}",
          delivery.getEventId(),
          delivery.getEventType(),
          delivery.getAddonName(),
          delivery.getAttempts(),
          error);
      return;
    }
    Duration pause = properties.retryBase().multipliedBy(1L << Math.min(delivery.getAttempts(), 16));
    if (pause.compareTo(properties.retryMax()) > 0) {
      pause = properties.retryMax();
    }
    delivery.retryAt(now.plus(pause), error);
    log.warn(
        "Lifecycle event {} ({}) not delivered to {} ({}), next attempt in {}",
        delivery.getEventId(),
        delivery.getEventType(),
        delivery.getAddonName(),
        error,
        pause);
  }

  private URI target(RegisteredAddon addon) {
    String base = addon.baseUrl().toString().replaceAll("/+$", "");
    return URI.create(base + properties.addonPath());
  }

  /** Service tokens need a Träger; events without one (should not happen) go out for the platform. */
  private static long tenant(EventDelivery delivery) {
    return delivery.getTenantId() != null ? delivery.getTenantId() : 0L;
  }
}
