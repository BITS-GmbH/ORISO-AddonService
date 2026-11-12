package org.oriso.addonservice.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("addons")
public record AddonProperties(
    @DefaultValue("/service/addons") String publicPath,
    @DefaultValue Discovery discovery,
    @DefaultValue Token token,
    @DefaultValue Gateway gateway,
    @DefaultValue UserService userService,
    @DefaultValue TechnicalClient technicalClient,
    @DefaultValue Calendar calendar,
    @DefaultValue Visibility visibility) {

  public record Discovery(
      @DefaultValue("false") boolean kubernetes,
      @DefaultValue("addons") String namespace,
      @DefaultValue("oriso.io/addon=true") String labelSelector,
      @DefaultValue("oriso.io/addon-port") String portAnnotation,
      @DefaultValue("https://kubernetes.default.svc") URI kubernetesApiUrl,
      @DefaultValue("/var/run/secrets/kubernetes.io/serviceaccount") String serviceAccountDir,
      @DefaultValue("5s") Duration manifestTimeout,
      List<URI> staticAddons) {

    public List<URI> staticAddons() {
      return staticAddons == null ? List.of() : staticAddons;
    }
  }

  public record Token(
      @DefaultValue("oriso-addonservice") String issuer,
      @DefaultValue("2m") Duration ttl,
      String privateKeyPem) {}

  public record Gateway(
      @DefaultValue("5s") Duration connectTimeout,
      @DefaultValue("60s") Duration readTimeout) {}

  /** The core UserService: agency and session checks, and delivery of add-on notifications. */
  public record UserService(
      @DefaultValue("http://userservice:8080") URI baseUrl,
      @DefaultValue("/users/data") String userDataPath,
      @DefaultValue("60s") Duration cacheTtl,
      @DefaultValue("/users/event-notifications/addon") String notificationsPath) {}

  /** Keycloak client the AddonService uses for its own calls to UserService (client credentials). */
  public record TechnicalClient(
      URI tokenUrl,
      @DefaultValue("backend-technical") String clientId,
      String clientSecret,
      @DefaultValue("30s") Duration renewBeforeExpiry) {}

  public record Calendar(
      @DefaultValue("3s") Duration timeout,
      @DefaultValue("200") int maxEntriesPerAddon,
      @DefaultValue("92d") Duration maxRange) {}

  public record Visibility(
      @DefaultValue("2s") Duration timeout,
      @DefaultValue("30s") Duration cacheTtl) {}
}
