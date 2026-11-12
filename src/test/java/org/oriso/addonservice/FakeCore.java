package org.oriso.addonservice;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Keycloak's token endpoint and UserService's notification endpoint, as far as the AddonService uses them. */
final class FakeCore implements AutoCloseable {

  final AtomicInteger tokenRequests = new AtomicInteger();
  final Map<String, String> lastTokenRequest = new ConcurrentHashMap<>();
  final Map<String, String> lastNotification = new ConcurrentHashMap<>();
  volatile int notificationStatus = 201;
  private final HttpServer server;

  FakeCore() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/token", this::token);
    server.createContext("/users/event-notifications/addon", this::notification);
    server.start();
  }

  URI baseUrl() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private void token(HttpExchange exchange) throws IOException {
    tokenRequests.incrementAndGet();
    lastTokenRequest.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    reply(exchange, 200, "{\"access_token\":\"service-token\",\"expires_in\":300,\"token_type\":\"Bearer\"}");
  }

  private void notification(HttpExchange exchange) throws IOException {
    lastNotification.put("authorization", exchange.getRequestHeaders().getFirst("Authorization"));
    lastNotification.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    reply(exchange, notificationStatus, notificationStatus < 300 ? "{\"id\":\"n-1\"}" : "{}");
  }

  private static void reply(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
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
