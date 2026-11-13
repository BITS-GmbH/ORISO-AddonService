package org.oriso.addonservice;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A minimal add-on: manifest, one echo API, one UI file and a calendar. Records the last requests. */
final class FakeAddon implements AutoCloseable {

  static final String MANIFEST =
      """
      {
        "name": "%s",
        "version": "0.1.0",
        "contract": "oriso-addon/v1",
        "react": "^19",
        "ui": "/ui/entry.js",
        "settings": "/schema/settings.json",
        "calendar": "/calendar",
        "events": ["user.deleted"],
        "stats": [
          { "id": "entries", "label": { "de": "Einträge" }, "type": "count" },
          { "id": "missing", "label": { "de": "Fehlt" }, "type": "count" }
        ],
        "apps": [
          { "id": "client-page", "title": { "de": "Demo" }, "slots": ["asker.navigation"] },
          { "id": "case-panel", "title": { "de": "Demo" }, "slots": ["consultant.session", "consultant.asker-profile"] }
        ]
      }
      """;

  static final String SCHEMA =
      """
      {
        "type": "object",
        "required": ["intro"],
        "properties": {
          "intro": { "type": "string", "title": "Einleitung", "maxLength": 20, "default": "Hallo" },
          "showHints": { "type": "boolean", "default": true },
          "limit": { "type": "integer", "minimum": 1, "maximum": 9 }
        }
      }
      """;

  final Map<String, String> lastRequest = new ConcurrentHashMap<>();
  final Map<String, String> lastStatsRequest = new ConcurrentHashMap<>();
  final Map<String, String> lastCalendarRequest = new ConcurrentHashMap<>();
  volatile int calendarStatus = 200;
  volatile String calendarBody = "{\"entries\":[]}";
  volatile long calendarDelayMs = 0;
  final java.util.List<Map<String, String>> events = new java.util.concurrent.CopyOnWriteArrayList<>();
  volatile int eventStatus = 204;
  private final HttpServer server;

  FakeAddon() throws IOException {
    this("demo");
  }

  FakeAddon(String name) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String manifest = MANIFEST.formatted(name);
    server.createContext("/.well-known/oriso-addon.json", ex -> reply(ex, 200, "application/json", manifest));
    server.createContext("/calendar", this::calendar);
    server.createContext("/schema/settings.json", ex -> reply(ex, 200, "application/json", SCHEMA));
    server.createContext("/stats", this::stats);
    server.createContext("/ui/", ex -> reply(ex, 200, "text/javascript", "export const mount = () => {};"));
    server.createContext("/api/", this::echo);
    server.createContext("/events", this::event);
    server.start();
  }

  private void stats(HttpExchange exchange) throws IOException {
    lastStatsRequest.put("uri", exchange.getRequestURI().toString());
    lastStatsRequest.put("authorization", exchange.getRequestHeaders().getFirst("Authorization"));
    reply(exchange, 200, "application/json", "{\"values\":{\"entries\":7}}");
  }

  private void calendar(HttpExchange exchange) throws IOException {
    lastCalendarRequest.put("uri", exchange.getRequestURI().toString());
    lastCalendarRequest.put("authorization", exchange.getRequestHeaders().getFirst("Authorization"));
    if (calendarDelayMs > 0) {
      try {
        Thread.sleep(calendarDelayMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    reply(exchange, calendarStatus, "application/json", calendarBody);
  }

  private void event(HttpExchange exchange) throws IOException {
    events.add(
        Map.of(
            "method", exchange.getRequestMethod(),
            "authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
            "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
    exchange.sendResponseHeaders(eventStatus, -1);
    exchange.close();
  }

  URI baseUrl() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private void echo(HttpExchange exchange) throws IOException {
    lastRequest.clear();
    lastRequest.put("method", exchange.getRequestMethod());
    lastRequest.put("uri", exchange.getRequestURI().toString());
    lastRequest.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    exchange.getRequestHeaders().forEach((name, values) -> lastRequest.put("header:" + name.toLowerCase(), values.getFirst()));
    exchange.getResponseHeaders().add("Set-Cookie", "leak=1");
    exchange.getResponseHeaders().add("X-Internal", "secret");
    reply(exchange, 201, "application/json", "{\"ok\":true}");
  }

  private static void reply(HttpExchange exchange, int status, String type, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", type);
    exchange.sendResponseHeaders(status, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
