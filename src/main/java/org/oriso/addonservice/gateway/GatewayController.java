package org.oriso.addonservice.gateway;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.oriso.addonservice.registry.AddonRegistry;
import org.oriso.addonservice.security.Caller;
import org.oriso.addonservice.token.AddonTokenIssuer;
import org.oriso.addonservice.web.AddonAccess;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code /addons/<name>/api/**} goes to the add-on's {@code /api/**} with a signed token for the
 * caller; {@code /addons/<name>/ui/**} serves the add-on's bundle and assets without login.
 */
@RestController
public class GatewayController {

  /** An SVG opened directly must not run script or load anything. */
  private static final Map<String, String> SVG_HEADERS =
      Map.of(
          "Content-Type", "image/svg+xml",
          "X-Content-Type-Options", "nosniff",
          "Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");

  private final AddonRegistry registry;
  private final AddonAccess access;
  private final AddonTokenIssuer tokens;
  private final AddonProxy proxy;

  public GatewayController(
      AddonRegistry registry, AddonAccess access, AddonTokenIssuer tokens, AddonProxy proxy) {
    this.registry = registry;
    this.access = access;
    this.tokens = tokens;
    this.proxy = proxy;
  }

  @RequestMapping("/addons/{name}/api/**")
  public void api(
      @PathVariable String name,
      @AuthenticationPrincipal Jwt jwt,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    Caller caller = Caller.from(jwt);
    var granted =
        access.require(
            name,
            caller,
            request.getHeader(AddonAccess.AGENCY_HEADER),
            request.getHeader(AddonAccess.SESSION_HEADER));
    var session = granted.session();
    String token =
        tokens.issue(
            name,
            caller,
            granted.agencyId(),
            session == null ? null : session.sessionId(),
            session == null ? null : session.askerId());
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", "Bearer " + token);
    proxy.forward(granted.addon(), "/api", remainder(request, name, "api"), request, response, headers);
  }

  @GetMapping("/addons/{name}/ui/**")
  public void ui(@PathVariable String name, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    var addon =
        registry
            .find(name)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown add-on"));
    String remainder = remainder(request, name, "ui");
    proxy.forward(addon, "/ui", remainder, request, response, Map.of(), -1, isSvg(remainder) ? SVG_HEADERS : Map.of());
  }

  private static boolean isSvg(String remainder) {
    return URLDecoder.decode(remainder, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).endsWith(".svg");
  }

  static String remainder(HttpServletRequest request, String name, String kind) {
    String uri = request.getRequestURI();
    String marker = "/addons/" + name + "/" + kind;
    int start = uri.indexOf(marker);
    return start < 0 ? "" : uri.substring(start + marker.length());
  }
}
