package org.oriso.addonservice.readme;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.oriso.addonservice.config.AddonProperties;
import org.oriso.addonservice.registry.RegisteredAddon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fetches the Markdown README an add-on names in its manifest, with its relative links made
 * absolute, and keeps it per add-on and version. Redirects are followed only on the same host.
 */
@Service
public class ReadmeService {

  private static final Logger log = LoggerFactory.getLogger(ReadmeService.class);
  private static final int MAX_REDIRECTS = 3;

  private final RestClient client;
  private final long maxBytes;
  private final Duration cacheTtl;
  private final Clock clock;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  public ReadmeService(AddonProperties properties, Clock clock) {
    var readme = properties.readme();
    var requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(readme.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    requestFactory.setReadTimeout(readme.timeout());
    this.client = RestClient.builder().requestFactory(requestFactory).build();
    this.maxBytes = readme.maxSize().toBytes();
    this.cacheTtl = readme.cacheTtl();
    this.clock = clock;
  }

  /** Empty when the add-on has no README; 502 when it cannot be fetched and nothing is cached. */
  public Optional<String> readme(RegisteredAddon addon) {
    if (!addon.manifest().hasReadme()) {
      return Optional.empty();
    }
    URI url = URI.create(addon.manifest().readme());
    String key = addon.name() + "@" + addon.manifest().version() + " " + url;
    Instant now = clock.instant();
    Cached cached = cache.get(key);
    if (cached != null && cached.loadedAt.plus(cacheTtl).isAfter(now)) {
      return Optional.of(cached.markdown);
    }
    try {
      String markdown = MarkdownLinks.absolutize(fetch(url), url);
      cache.put(key, new Cached(markdown, now));
      return Optional.of(markdown);
    } catch (Exception e) {
      log.warn("README of {} not available from {}: {}", addon.name(), url, e.getMessage());
      if (cached != null) {
        return Optional.of(cached.markdown);
      }
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "README not available");
    }
  }

  private String fetch(URI url) {
    URI current = url;
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      Response response = client.get().uri(current).exchange((request, res) -> {
        if (res.getStatusCode().is3xxRedirection()) {
          return Response.redirect(res.getHeaders().getLocation());
        }
        if (!res.getStatusCode().is2xxSuccessful()) {
          throw new IOException("status " + res.getStatusCode().value());
        }
        return Response.body(read(res.getBody()));
      });
      if (response.body != null) {
        return response.body;
      }
      URI next = response.location == null ? null : current.resolve(response.location);
      if (next == null
          || !Objects.equals(next.getScheme(), url.getScheme())
          || !Objects.equals(next.getHost(), url.getHost())
          || next.getPort() != url.getPort()) {
        throw new IllegalStateException("redirect to " + next + " refused");
      }
      current = next;
    }
    throw new IllegalStateException("too many redirects");
  }

  private String read(InputStream body) throws IOException {
    byte[] bytes = body.readNBytes((int) maxBytes + 1);
    if (bytes.length > maxBytes) {
      throw new IOException("larger than " + maxBytes + " bytes");
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private record Response(String body, URI location) {
    static Response redirect(URI location) {
      return new Response(null, location);
    }

    static Response body(String body) {
      return new Response(body, null);
    }
  }

  private record Cached(String markdown, Instant loadedAt) {}
}
