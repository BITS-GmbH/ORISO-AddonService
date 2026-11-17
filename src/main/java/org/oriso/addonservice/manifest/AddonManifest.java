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
    String health,
    String calendar,
    String readme) {

  public static final String CONTRACT_V1 = "oriso-addon/v1";

  public boolean hasReadme() {
    return readme != null && !readme.isBlank();
  }

  public List<App> apps() {
    return apps == null ? List.of() : apps;
  }

  public List<Stat> stats() {
    return stats == null ? List.of() : stats;
  }

  public List<String> events() {
    return events == null ? List.of() : events;
  }

  /** The same manifest with app icons as paths below {@code publicPath}, as the browser loads them. */
  public AddonManifest withPublicIcons(String publicPath) {
    String base = publicPath + "/" + name;
    var withIcons = apps().stream().map(app -> app.icon() == null ? app : app.withIcon(base + app.icon())).toList();
    return new AddonManifest(
        name, version, contract, react, withIcons, ui, settings, stats, export, events, health, calendar, readme);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record App(
      String id,
      Map<String, String> title,
      List<String> slots,
      String visibility,
      List<Screenshot> screenshots,
      String icon,
      Map<String, String> description) {

    public static final String STATIC = "static";
    public static final String DYNAMIC = "dynamic";

    public App(String id, Map<String, String> title, List<String> slots, String visibility) {
      this(id, title, slots, visibility, null, null, null);
    }

    public App(
        String id, Map<String, String> title, List<String> slots, String visibility, List<Screenshot> screenshots) {
      this(id, title, slots, visibility, screenshots, null, null);
    }

    public App(
        String id,
        Map<String, String> title,
        List<String> slots,
        String visibility,
        List<Screenshot> screenshots,
        String icon) {
      this(id, title, slots, visibility, screenshots, icon, null);
    }

    public App withIcon(String newIcon) {
      return new App(id, title, slots, visibility, screenshots, newIcon, description);
    }

    public List<String> slots() {
      return slots == null ? List.of() : slots;
    }

    public List<Screenshot> screenshots() {
      return screenshots == null ? List.of() : screenshots;
    }

    public boolean isDynamic() {
      return DYNAMIC.equals(visibility);
    }
  }

  /** An image of an app in one of its slots, shown in ORISO-Admin. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Screenshot(String slot, String url, Map<String, String> caption) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Stat(String id, Map<String, String> label, String type) {}
}
