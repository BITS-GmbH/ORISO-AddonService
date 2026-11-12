package org.oriso.addonservice.token;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.oriso.addonservice.config.AddonProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Keycloak access token of the backend-technical client (client credentials), for the
 * AddonService's own calls to UserService. Kept until shortly before it expires.
 */
@Component
public class TechnicalClientToken {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final AddonProperties.TechnicalClient properties;
  private final Clock clock;
  private final RestClient keycloak;
  private Cached cached;

  public TechnicalClientToken(AddonProperties properties, Clock clock) {
    this.properties = properties.technicalClient();
    this.clock = clock;
    var requestFactory =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    requestFactory.setReadTimeout(TIMEOUT);
    this.keycloak = RestClient.builder().requestFactory(requestFactory).build();
  }

  public synchronized String get() {
    Instant now = clock.instant();
    if (cached != null && now.isBefore(cached.renewAt)) {
      return cached.token;
    }
    if (properties.tokenUrl() == null || properties.clientSecret() == null || properties.clientSecret().isBlank()) {
      throw new IllegalStateException("addons.technical-client.token-url and client-secret are not configured");
    }
    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", properties.clientId());
    form.add("client_secret", properties.clientSecret());
    Map<?, ?> body =
        keycloak
            .post()
            .uri(properties.tokenUrl())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map.class);
    if (body == null || !(body.get("access_token") instanceof String token)) {
      throw new IllegalStateException("Keycloak answered without an access token");
    }
    long expiresIn = body.get("expires_in") instanceof Number seconds ? seconds.longValue() : 60;
    cached = new Cached(token, now.plusSeconds(expiresIn).minus(properties.renewBeforeExpiry()));
    return token;
  }

  public synchronized void invalidate() {
    cached = null;
  }

  private record Cached(String token, Instant renewAt) {}
}
