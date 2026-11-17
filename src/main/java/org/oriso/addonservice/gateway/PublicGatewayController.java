package org.oriso.addonservice.gateway;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.oriso.addonservice.config.AddonProperties;
import org.oriso.addonservice.registration.RegistrationAddons;
import org.oriso.addonservice.registry.AddonRegistry;
import org.oriso.addonservice.token.AddonTokenIssuer;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code /addons/<name>/public/api/**} goes to the add-on's {@code /public/api/**} without login,
 * for visitors in the registration. Only add-ons that offer {@code registration.agency-finder} for
 * the Träger in {@code X-Oriso-Tenant-Id}; rate limited per client address and add-on.
 */
@RestController
public class PublicGatewayController {

  public static final String TENANT_HEADER = "X-Oriso-Tenant-Id";

  private final AddonRegistry registry;
  private final RegistrationAddons registration;
  private final AddonTokenIssuer tokens;
  private final AddonProxy proxy;
  private final RateLimiter rateLimiter;
  private final long maxBodyBytes;

  public PublicGatewayController(
      AddonRegistry registry,
      RegistrationAddons registration,
      AddonTokenIssuer tokens,
      AddonProxy proxy,
      RateLimiter rateLimiter,
      AddonProperties properties) {
    this.registry = registry;
    this.registration = registration;
    this.tokens = tokens;
    this.proxy = proxy;
    this.rateLimiter = rateLimiter;
    this.maxBodyBytes = properties.publicGateway().maxBodySize().toBytes();
  }

  @RequestMapping("/addons/{name}/public/api/**")
  public void api(@PathVariable String name, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (!rateLimiter.tryAcquire(clientAddress(request) + "|" + name)) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests");
    }
    long tenantId = tenantId(request.getHeader(TENANT_HEADER));
    var addon =
        registry
            .find(name)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown add-on"));
    if (!registration.isAllowed(name, tenantId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Add-on not available in the registration");
    }
    var headers = Map.of("Authorization", "Bearer " + tokens.issueAnonymous(name, tenantId));
    proxy.forward(
        addon,
        "/public/api",
        GatewayController.remainder(request, name, "public/api"),
        request,
        response,
        headers,
        maxBodyBytes,
        Map.of());
  }

  /** ingress-nginx puts the client first in X-Forwarded-For. */
  static String clientAddress(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }

  private static long tenantId(String header) {
    if (header == null || header.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, TENANT_HEADER + " is required");
    }
    try {
      return Long.parseLong(header.trim());
    } catch (NumberFormatException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + TENANT_HEADER);
    }
  }
}
