package org.oriso.addonservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.oriso.addonservice.config.AddonProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SessionAccessTest {

  private final Caller counsellor =
      new Caller("consultant-1", "berater", Set.of("consultant"), 1L, "user-token");
  private final AtomicInteger calls = new AtomicInteger();
  private int status;
  private String body;
  private HttpServer userService;
  private SessionAccess sessions;

  @BeforeEach
  void start() throws Exception {
    userService = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    userService.createContext(
        "/users/consultants/sessions/",
        exchange -> {
          calls.incrementAndGet();
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    userService.start();
    URI baseUrl = URI.create("http://127.0.0.1:" + userService.getAddress().getPort());
    var properties =
        new AddonProperties(
            "/service/addons",
            null,
            null,
            null,
            new AddonProperties.UserService(
                baseUrl, "/users/data", Duration.ofSeconds(60), "/users/event-notifications/addon"),
            null,
            null);
    sessions = new SessionAccess(properties, Clock.systemUTC());
  }

  @AfterEach
  void stop() {
    userService.stop(0);
  }

  @Test
  void returnsTheClientOfAnAccessibleSession() {
    status = 200;
    body = "{\"askerId\":\"asker-7\"}";

    assertThat(sessions.askerOf(counsellor, 16)).contains("asker-7");
  }

  @Test
  void deniedSessionIsEmptyAndCached() {
    status = 403;
    body = "{}";

    assertThat(sessions.askerOf(counsellor, 16)).isEmpty();
    assertThat(sessions.askerOf(counsellor, 16)).isEmpty();
    assertThat(calls).hasValue(1);
  }

  @Test
  void userServiceFailureIsABadGatewayAndNotCached() {
    status = 500;
    body = "{}";

    assertThatThrownBy(() -> sessions.askerOf(counsellor, 16))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_GATEWAY);

    status = 200;
    body = "{\"askerId\":\"asker-7\"}";
    assertThat(sessions.askerOf(counsellor, 16)).contains("asker-7");
    assertThat(calls).hasValue(2);
  }
}
