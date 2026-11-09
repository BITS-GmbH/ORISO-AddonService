package org.oriso.addonservice.gateway;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.oriso.addonservice.config.AddonProperties;
import org.oriso.addonservice.registry.RegisteredAddon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Forwards a request to an add-on. Only an allowlist of headers passes in either direction:
 * cookies, the user's Authorization header and client addresses never reach the add-on.
 */
@Component
public class AddonProxy {

  private static final Logger log = LoggerFactory.getLogger(AddonProxy.class);

  private static final Set<String> REQUEST_HEADERS =
      Set.of("accept", "accept-language", "content-type", "if-none-match", "if-modified-since", "range");

  private static final Set<String> RESPONSE_HEADERS =
      Set.of(
          "content-type",
          "cache-control",
          "etag",
          "last-modified",
          "content-disposition",
          "content-language",
          "content-range",
          "accept-ranges",
          "expires");

  private static final Set<String> METHODS_WITH_BODY = Set.of("POST", "PUT", "PATCH", "DELETE");

  private final HttpClient client;
  private final AddonProperties.Gateway properties;

  public AddonProxy(AddonProperties properties) {
    this.properties = properties.gateway();
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(this.properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /**
   * @param targetPrefix the add-on path the request maps to, {@code /api} or {@code /ui}
   * @param remainder the rest of the request path after that prefix, still URL encoded
   */
  public void forward(
      RegisteredAddon addon,
      String targetPrefix,
      String remainder,
      HttpServletRequest request,
      HttpServletResponse response,
      Map<String, String> extraHeaders)
      throws IOException {
    URI target = target(addon.baseUrl(), targetPrefix, remainder, request.getQueryString());
    String method = request.getMethod().toUpperCase(Locale.ROOT);

    HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(properties.readTimeout());
    for (String name : REQUEST_HEADERS) {
      String value = request.getHeader(name);
      if (value != null) {
        builder.header(name, value);
      }
    }
    extraHeaders.forEach(builder::header);
    builder.method(method, bodyOf(method, request));

    HttpResponse<InputStream> upstream;
    try {
      upstream = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Add-on request interrupted");
    } catch (IOException e) {
      log.warn("Add-on {} not reachable at {}: {}", addon.name(), target, e.getMessage());
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Add-on not reachable");
    }

    response.setStatus(upstream.statusCode());
    upstream
        .headers()
        .map()
        .forEach(
            (name, values) -> {
              if (RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> response.addHeader(name, value));
              }
            });
    try (InputStream body = upstream.body(); OutputStream out = response.getOutputStream()) {
      body.transferTo(out);
    }
  }

  static URI target(URI baseUrl, String targetPrefix, String remainder, String query) {
    String decoded = URLDecoder.decode(remainder, StandardCharsets.UTF_8);
    if (decoded.contains("..") || decoded.contains("\\") || remainder.contains("//")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path");
    }
    String path = targetPrefix + (remainder.isEmpty() || remainder.startsWith("/") ? remainder : "/" + remainder);
    String base = baseUrl.toString().replaceAll("/+$", "");
    return URI.create(base + path + (query == null || query.isEmpty() ? "" : "?" + query));
  }

  private static HttpRequest.BodyPublisher bodyOf(String method, HttpServletRequest request)
      throws IOException {
    if (!METHODS_WITH_BODY.contains(method)) {
      return HttpRequest.BodyPublishers.noBody();
    }
    InputStream in = request.getInputStream();
    return HttpRequest.BodyPublishers.ofInputStream(() -> in);
  }
}
