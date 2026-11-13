package org.oriso.addonservice.host;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.oriso.addonservice.token.TechnicalClientToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reads from core services (UserService, TenantService) with the AddonService's technical client
 * token. A 404 of the core service stays a 404; every other failure becomes a 502, so an add-on
 * can tell "does not exist" from "core not reachable".
 */
@Component
public class CoreReadClient {

  private static final Logger log = LoggerFactory.getLogger(CoreReadClient.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final RestClient http;
  private final TechnicalClientToken technicalToken;

  public CoreReadClient(TechnicalClientToken technicalToken) {
    var requestFactory =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    requestFactory.setReadTimeout(TIMEOUT);
    this.http = RestClient.builder().requestFactory(requestFactory).build();
    this.technicalToken = technicalToken;
  }

  /** GETs {@code uri} as JSON object; {@code what} names the read in logs and errors. */
  public Map<String, Object> get(URI uri, String what) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body =
          http.get()
              .uri(uri)
              .header("Authorization", "Bearer " + technicalToken.get())
              // The core services read the Träger of a technical call from this header; 0 = platform.
              .header("tenantId", "0")
              .retrieve()
              .body(Map.class);
      if (body == null) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, what + ": empty answer");
      }
      return body;
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      if (status == 404) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, what + ": not found");
      }
      if (status == 401) {
        technicalToken.invalidate();
      }
      log.warn("{}: core service answered {}", what, status);
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, what + ": core service refused");
    } catch (RestClientException | IllegalStateException e) {
      log.warn("{}: core service not reachable: {}", what, e.getMessage());
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, what + ": core service not reachable");
    }
  }
}
