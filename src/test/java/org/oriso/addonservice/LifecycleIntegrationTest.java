package org.oriso.addonservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.oriso.addonservice.discovery.AddonDiscovery;
import org.oriso.addonservice.lifecycle.EventDeliveryRepository;
import org.oriso.addonservice.lifecycle.EventDispatcher;
import org.oriso.addonservice.lifecycle.LifecycleEvents;
import org.oriso.addonservice.token.AddonTokenIssuer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.oauth2.jwt.Jwt;

/** Lifecycle events: queued per subscribed add-on, delivered with a service token, retried. */
@SpringBootTest
@ActiveProfiles("test")
class LifecycleIntegrationTest {

  private static final FakeAddon ADDON = start(() -> new FakeAddon());
  /** Starts at real time: the add-on side validates the service token against the real clock. */
  private static final Instant START = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
  private static final MutableClock CLOCK = new MutableClock(START);

  @Autowired AddonDiscovery discovery;
  @Autowired LifecycleEvents events;
  @Autowired EventDispatcher dispatcher;
  @Autowired EventDeliveryRepository deliveries;
  @Autowired AddonTokenIssuer issuer;

  @TestConfiguration
  static class Clocks {
    @Bean
    @Primary
    java.time.Clock testClock() {
      return CLOCK;
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("addons.discovery.static-addons", () -> ADDON.baseUrl().toString());
    registry.add("spring.main.allow-bean-definition-overriding", () -> "true");
  }

  @AfterAll
  static void stop() {
    ADDON.close();
  }

  private interface Starter<T> {
    T start() throws Exception;
  }

  private static <T> T start(Starter<T> starter) {
    try {
      return starter.start();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @BeforeEach
  void setUp() {
    deliveries.deleteAll();
    discovery.refresh();
    ADDON.events.clear();
    ADDON.eventStatus = 204;
    CLOCK.set(START);
  }

  private static final String USER_DELETED =
      """
      {"id":"ev-1","type":"user.deleted","occurredAt":"2026-11-13T09:59:00Z","tenantId":1,"userId":"asker-7","role":"asker"}
      """;

  @Test
  void deliversASubscribedEventOnceWithAServiceToken() throws Exception {
    assertThat(events.accept(USER_DELETED)).isEqualTo(1);
    assertThat(events.accept(USER_DELETED)).isZero();

    assertThat(dispatcher.dispatch()).isEqualTo(1);
    assertThat(ADDON.events).singleElement().satisfies(request -> {
      assertThat(request.get("method")).isEqualTo("POST");
      assertThat(request.get("body")).contains("\"userId\":\"asker-7\"");
      Jwt jwt = issuer.decoder().decode(request.get("authorization").substring("Bearer ".length()));
      assertThat(jwt.getAudience()).containsExactly("demo");
      assertThat(jwt.getClaimAsStringList("roles")).containsExactly(AddonTokenIssuer.SERVICE_ROLE);
      assertThat(((Number) jwt.getClaim("tenantId")).longValue()).isEqualTo(1L);
    });
    assertThat(deliveries.findByEventId("ev-1")).singleElement().satisfies(row -> {
      assertThat(row.getDeliveredAt()).isNotNull();
      assertThat(row.getAttempts()).isEqualTo(1);
    });
    assertThat(dispatcher.dispatch()).isZero();
  }

  @Test
  void ignoresUnsubscribedUnknownAndMalformedEvents() {
    assertThat(events.accept("{\"id\":\"ev-2\",\"type\":\"session.deleted\",\"tenantId\":1,\"sessionId\":4}")).isZero();
    assertThat(events.accept("{\"id\":\"ev-3\",\"type\":\"tenant.renamed\"}")).isZero();
    assertThat(events.accept("{\"type\":\"user.deleted\"}")).isZero();
    assertThat(events.accept("not json")).isZero();
    assertThat(deliveries.count()).isZero();
  }

  @Test
  void retriesWithGrowingPausesAndGivesUpAfterTheLimit() {
    ADDON.eventStatus = 500;
    events.accept(USER_DELETED);

    dispatcher.dispatch();
    var row = deliveries.findByEventId("ev-1").getFirst();
    assertThat(row.getDeliveredAt()).isNull();
    assertThat(row.getLastError()).isEqualTo("HTTP 500");
    assertThat(row.getNextAttemptAt()).isEqualTo(START.plusSeconds(30));
    assertThat(dispatcher.dispatch()).isZero();

    CLOCK.advance(Duration.ofSeconds(30));
    ADDON.eventStatus = 204;
    assertThat(dispatcher.dispatch()).isEqualTo(1);
    assertThat(deliveries.findByEventId("ev-1").getFirst().getDeliveredAt()).isNotNull();

    deliveries.deleteAll();
    ADDON.eventStatus = 503;
    events.accept(USER_DELETED.replace("ev-1", "ev-9"));
    CLOCK.advance(Duration.ofDays(8));
    dispatcher.dispatch();
    var failed = deliveries.findByEventId("ev-9").getFirst();
    assertThat(failed.getFailedAt()).isNotNull();
    CLOCK.advance(Duration.ofDays(1));
    assertThat(dispatcher.dispatch()).isZero();
  }

  /** A clock the test moves. */
  static final class MutableClock extends java.time.Clock {
    private volatile Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void set(Instant instant) {
      now = instant;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public java.time.Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
