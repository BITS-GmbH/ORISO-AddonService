package org.oriso.addonservice.discovery;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.oriso.addonservice.config.AddonProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Lists add-on Services through the Kubernetes API with the pod's service account. Needs only
 * {@code get/list} on Services in the add-on namespace.
 */
public class KubernetesServiceLister {

  private final AddonProperties.Discovery properties;

  public KubernetesServiceLister(AddonProperties.Discovery properties) {
    this.properties = properties;
  }

  public List<URI> list() throws IOException, GeneralSecurityException {
    Path accountDir = Path.of(properties.serviceAccountDir());
    String token = Files.readString(accountDir.resolve("token")).trim();
    HttpClient httpClient =
        HttpClient.newBuilder().sslContext(sslContext(accountDir.resolve("ca.crt"))).build();
    RestClient client =
        RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory(httpClient))
            .baseUrl(properties.kubernetesApiUrl().toString())
            .build();

    String path =
        "/api/v1/namespaces/"
            + properties.namespace()
            + "/services?labelSelector="
            + URLEncoder.encode(properties.labelSelector(), StandardCharsets.UTF_8);
    Map<?, ?> response =
        client
            .get()
            .uri(URI.create(properties.kubernetesApiUrl() + path))
            .header("Authorization", "Bearer " + token)
            .retrieve()
            .body(Map.class);
    return toBaseUrls(response, properties.portAnnotation());
  }

  static List<URI> toBaseUrls(Map<?, ?> serviceList, String portAnnotation) {
    var urls = new ArrayList<URI>();
    if (serviceList == null || !(serviceList.get("items") instanceof Collection<?> items)) {
      return urls;
    }
    for (Object item : items) {
      if (!(item instanceof Map<?, ?> service)
          || !(service.get("metadata") instanceof Map<?, ?> metadata)) {
        continue;
      }
      Object name = metadata.get("name");
      Object namespace = metadata.get("namespace");
      Integer port = port(service, metadata, portAnnotation);
      if (name != null && namespace != null && port != null) {
        urls.add(URI.create("http://" + name + "." + namespace + ".svc:" + port));
      }
    }
    return urls;
  }

  private static Integer port(Map<?, ?> service, Map<?, ?> metadata, String portAnnotation) {
    if (metadata.get("annotations") instanceof Map<?, ?> annotations
        && annotations.get(portAnnotation) instanceof String annotated) {
      try {
        return Integer.parseInt(annotated.trim());
      } catch (NumberFormatException ignored) {
        // fall through to the first declared port
      }
    }
    if (service.get("spec") instanceof Map<?, ?> spec
        && spec.get("ports") instanceof List<?> ports
        && !ports.isEmpty()
        && ports.getFirst() instanceof Map<?, ?> first
        && first.get("port") instanceof Number number) {
      return number.intValue();
    }
    return null;
  }

  private static SSLContext sslContext(Path caFile) throws IOException, GeneralSecurityException {
    KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustStore.load(null, null);
    try (InputStream in = Files.newInputStream(caFile)) {
      int i = 0;
      for (var certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
        trustStore.setCertificateEntry("kubernetes-ca-" + i++, certificate);
      }
    }
    TrustManagerFactory trustManagers =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trustStore);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, trustManagers.getTrustManagers(), null);
    return context;
  }
}
