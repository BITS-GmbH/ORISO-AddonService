package org.oriso.addonservice.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KubernetesServiceListerTest {

  @Test
  void buildsClusterUrlsFromServicesPreferringThePortAnnotation() {
    Map<String, Object> list =
        Map.of(
            "items",
            List.of(
                Map.of(
                    "metadata", Map.of("name", "tools", "namespace", "addons"),
                    "spec", Map.of("ports", List.of(Map.of("port", 8080)))),
                Map.of(
                    "metadata",
                        Map.of("name", "surveys", "namespace", "addons", "annotations", Map.of("oriso.io/addon-port", "9000")),
                    "spec", Map.of("ports", List.of(Map.of("port", 80)))),
                Map.of("metadata", Map.of("name", "no-ports", "namespace", "addons"))));

    assertThat(KubernetesServiceLister.toBaseUrls(list, "oriso.io/addon-port"))
        .containsExactly(URI.create("http://tools.addons.svc:8080"), URI.create("http://surveys.addons.svc:9000"));
  }
}
