package org.oriso.addonservice.readme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.oriso.addonservice.config.AddonProperties;
import org.oriso.addonservice.manifest.AddonManifest;
import org.oriso.addonservice.registry.RegisteredAddon;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

class ReadmeServiceTest {

  private HttpServer server;
  private final AtomicInteger requests = new AtomicInteger();
  private volatile int status = 200;
  private volatile String body = "# Demo\n![shot](img/a.png)\n";
  private final MutableClock clock = new MutableClock();
  private ReadmeService readmes;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/docs/README.md", exchange -> {
      requests.incrementAndGet();
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      try (var out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    });
    server.createContext("/moved.md", exchange -> {
      exchange.getResponseHeaders().add("Location", "/docs/README.md");
      exchange.sendResponseHeaders(301, -1);
      exchange.close();
    });
    server.createContext("/elsewhere.md", exchange -> {
      exchange.getResponseHeaders().add("Location", "https://evil.example/README.md");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });
    server.start();
    var properties =
        new AddonProperties(
            "/service/addons", null, null, null, null, null, null, null, null, null,
            new AddonProperties.Readme(List.of("127.0.0.1"), Duration.ofSeconds(2), DataSize.ofKilobytes(1), Duration.ofMinutes(10)));
    readmes = new ReadmeService(properties, clock);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  private static RegisteredAddon addon(String version, String readme) {
    var manifest =
        new AddonManifest(
            "demo", version, AddonManifest.CONTRACT_V1, "^19",
            List.of(new AddonManifest.App("a", Map.of("de", "A"), List.of("asker.navigation"), null)),
            "/ui/e.js", null, null, null, null, null, null, readme);
    return new RegisteredAddon(manifest, URI.create("http://demo"), "static", Instant.EPOCH);
  }

  @Test
  void isEmptyWithoutReadme() {
    assertThat(readmes.readme(addon("1.0.0", null))).isEmpty();
  }

  @Test
  void fetchesWithAbsoluteLinksAndCachesPerVersion() {
    var addon = addon("1.0.0", url("/docs/README.md"));
    assertThat(readmes.readme(addon)).hasValue("# Demo\n![shot](" + url("/docs/img/a.png") + ")\n");
    readmes.readme(addon);
    assertThat(requests).hasValue(1);

    readmes.readme(addon("1.0.1", url("/docs/README.md")));
    assertThat(requests).hasValue(2);

    clock.advance(Duration.ofMinutes(11));
    readmes.readme(addon);
    assertThat(requests).hasValue(3);
  }

  @Test
  void keepsServingTheCachedCopyWhenTheHostFails() {
    var addon = addon("1.0.0", url("/docs/README.md"));
    readmes.readme(addon);
    status = 500;
    clock.advance(Duration.ofMinutes(11));
    assertThat(readmes.readme(addon)).hasValueSatisfying(markdown -> assertThat(markdown).startsWith("# Demo"));
    assertThat(requests).hasValue(2);
  }

  @Test
  void answers502WithoutCachedCopy() {
    status = 404;
    assertBadGateway(addon("1.0.0", url("/docs/README.md")));
  }

  @Test
  void refusesReadmesLargerThanTheLimit() {
    body = "x".repeat(1025);
    assertBadGateway(addon("1.0.0", url("/docs/README.md")));
    body = "x".repeat(1024);
    assertThat(readmes.readme(addon("1.0.0", url("/docs/README.md")))).hasValue(body);
  }

  @Test
  void followsRedirectsOnTheSameHostOnly() {
    assertThat(readmes.readme(addon("1.0.0", url("/moved.md")))).isPresent();
    assertBadGateway(addon("1.0.0", url("/elsewhere.md")));
  }

  private void assertBadGateway(RegisteredAddon addon) {
    assertThatThrownBy(() -> readmes.readme(addon))
        .isInstanceOfSatisfying(
            ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }
}
