package org.oriso.addonservice;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A minimal add-on: manifest, one echo API and one UI file. Records the last API request. */
final class FakeAddon implements AutoCloseable {

  static final String MANIFEST =
      """
      {
        "name": "demo",
        "version": "0.1.0",
        "contract": "oriso-addon/v1",
        "react": "^19",
        "ui": "/ui/entry.js",
        "settings": "/schema/settings.json",
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
  private final HttpServer server;

  FakeAddon() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/.well-known/oriso-addon.json", ex -> reply(ex, 200, "application/json", MANIFEST));
    server.createContext("/schema/settings.json", ex -> reply(ex, 200, "application/json", SCHEMA));
    server.createContext("/stats", this::stats);
    server.createContext("/ui/", ex -> reply(ex, 200, "text/javascript", "export const mount = () => {};"));
    server.createContext("/api/", this::echo);
    server.start();
  }

  private void stats(HttpExchange exchange) throws IOException {
    lastStatsRequest.put("uri", exchange.getRequestURI().toString());
    lastStatsRequest.put("authorization", exchange.getRequestHeaders().getFirst("Authorization"));
    reply(exchange, 200, "application/json", "{\"values\":{\"entries\":7}}");
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
