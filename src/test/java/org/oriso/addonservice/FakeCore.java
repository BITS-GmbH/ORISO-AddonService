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
  final java.util.List<String> notifications = new java.util.concurrent.CopyOnWriteArrayList<>();
  final Map<String, String> lastGateRequest = new ConcurrentHashMap<>();
  final Map<String, String> lastContextRequest = new ConcurrentHashMap<>();
  final AtomicInteger gateRequests = new AtomicInteger();
  volatile int contextStatus = 200;
  volatile String contextBody = "{}";
  private final HttpServer server;

  FakeCore() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/token", this::token);
    server.createContext("/users/event-notifications/addon", this::notification);
    server.createContext("/users/addon-context", this::context);
    server.createContext("/tenantadmin/", this::gate);
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
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    lastNotification.put("body", body);
    notifications.add(body);
    reply(exchange, notificationStatus, notificationStatus < 300 ? "{\"id\":\"n-1\"}" : "{}");
  }

  private void context(HttpExchange exchange) throws IOException {
    lastContextRequest.put("uri", exchange.getRequestURI().toString());
    lastContextRequest.put("authorization", exchange.getRequestHeaders().getFirst("Authorization"));
    reply(exchange, contextStatus, contextBody);
  }

  private void gate(HttpExchange exchange) throws IOException {
    gateRequests.incrementAndGet();
    lastGateRequest.put("uri", exchange.getRequestURI().toString());
    lastGateRequest.put("authorization", exchange.getRequestHeaders().getFirst("Authorization"));
    reply(
        exchange,
        200,
        "{\"dpaStatus\":\"OUTDATED\",\"dpaSigned\":false,\"newCounsellingAllowed\":false,"
            + "\"renewalGraceActive\":false,\"signingDeadlineAt\":\"2026-11-13T15:28:00Z\",\"dpaPublished\":true}");
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
