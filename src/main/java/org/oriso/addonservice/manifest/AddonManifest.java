package org.oriso.addonservice.manifest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/** What an add-on serves at {@code GET /.well-known/oriso-addon.json}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AddonManifest(
    String name,
    String version,
    String contract,
    String react,
    List<App> apps,
    String ui,
    String settings,
    List<Stat> stats,
    String export,
    List<String> events,
    String health) {

  public static final String CONTRACT_V1 = "oriso-addon/v1";

  public List<App> apps() {
    return apps == null ? List.of() : apps;
  }

  public List<Stat> stats() {
    return stats == null ? List.of() : stats;
  }

  public List<String> events() {
    return events == null ? List.of() : events;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record App(String id, Map<String, String> title, List<String> slots) {

    public List<String> slots() {
      return slots == null ? List.of() : slots;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Stat(String id, Map<String, String> label, String type) {}
}
