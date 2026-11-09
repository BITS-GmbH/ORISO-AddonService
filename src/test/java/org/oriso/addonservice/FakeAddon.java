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
        "apps": [
          { "id": "client-page", "title": { "de": "Demo" }, "slots": ["asker.navigation"] },
          { "id": "case-panel", "title": { "de": "Demo" }, "slots": ["consultant.session", "consultant.asker-profile"] }
        ]
      }
      """;

  final Map<String, String> lastRequest = new ConcurrentHashMap<>();
  private final HttpServer server;

  FakeAddon() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/.well-known/oriso-addon.json", ex -> reply(ex, 200, "application/json", MANIFEST));
    server.createContext("/ui/", ex -> reply(ex, 200, "text/javascript", "export const mount = () => {};"));
    server.createContext("/api/", this::echo);
    server.start();
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
