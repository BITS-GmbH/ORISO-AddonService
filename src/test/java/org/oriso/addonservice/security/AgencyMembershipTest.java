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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.oriso.addonservice.config.AddonProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AgencyMembershipTest {

  private final Caller caller = new Caller("user-1", "jane", Set.of("user"), 1L, "user-token");
  private int status;
  private String body;
  private HttpServer userService;
  private AgencyMembership membership;

  @BeforeEach
  void start() throws Exception {
    userService = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    userService.createContext(
        "/users/data",
        exchange -> {
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
            null,
            null);
    membership = new AgencyMembership(properties, Clock.systemUTC());
  }

  @AfterEach
  void stop() {
    userService.stop(0);
  }

  @Test
  void counsellorsHaveTheirAgencies() {
    status = 200;
    body = "{\"agencies\":[{\"id\":10},{\"id\":11}]}";

    assertThat(membership.agenciesOf(caller)).containsExactlyInAnyOrder(10L, 11L);
  }

  @Test
  void clientsHaveTheAgenciesOfTheirSessionsAndRegistrations() {
    status = 200;
    body =
        """
        { "sessions": [ { "id": 1, "agencyId": 10 }, { "id": 2, "agencyId": 12 } ],
          "consultingTypes": {
            "1": { "isRegistered": true, "agency": { "id": 10 } },
            "2": { "isRegistered": true, "agency": { "id": 13 } },
            "3": { "isRegistered": false, "agency": null } } }
        """;

    assertThat(membership.agenciesOf(caller)).containsExactlyInAnyOrder(10L, 12L, 13L);
    assertThat(membership.belongsTo(caller, 13L)).isTrue();
  }

  @Test
  void aFailingUserServiceIsNoAgenciesOrABadGateway() {
    status = 500;
    body = "{}";

    assertThat(membership.agenciesOf(caller)).isEmpty();
    assertThatThrownBy(() -> membership.requireAgenciesOf(caller))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.BAD_GATEWAY);
  }
}
