package org.oriso.addonservice.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManifestValidatorTest {

  private static AddonManifest manifest(String name, String contract, String ui, List<AddonManifest.App> apps) {
    return withCalendar(name, contract, ui, apps, null);
  }

  private static AddonManifest withCalendar(
      String name, String contract, String ui, List<AddonManifest.App> apps, String calendar) {
    return new AddonManifest(name, "1.0.0", contract, "^19", apps, ui, null, null, null, null, "/health", calendar, null);
  }

  private static AddonManifest withReadme(String readme) {
    return new AddonManifest(
        "demo", "1.0.0", "oriso-addon/v1", "^19", List.of(app("goals", "asker.navigation")), "/ui/e.js",
        null, null, null, null, "/health", null, readme);
  }

  private static AddonManifest.App app(String id, String... slots) {
    return withVisibility(id, null, slots);
  }

  private static AddonManifest.App withVisibility(String id, String visibility, String... slots) {
    return new AddonManifest.App(id, Map.of("de", "Titel"), List.of(slots), visibility);
  }

  @Test
  void acceptsStaticOrDynamicVisibilityOnly() {
    var apps =
        List.of(
            withVisibility("a", "static", "asker.navigation"),
            withVisibility("b", "dynamic", "asker.navigation"),
            withVisibility("c", "sometimes", "asker.navigation"));
    assertThat(ManifestValidator.problems(manifest("demo", "oriso-addon/v1", "/ui/e.js", apps)))
        .containsExactly("app 'c' visibility must be 'static' or 'dynamic'");
  }

  @Test
  void acceptsAValidManifest() {
    var valid = manifest("digisucht-tools", "oriso-addon/v1", "/ui/entry.js", List.of(app("goals", "asker.navigation")));
    assertThat(ManifestValidator.problems(valid)).isEmpty();
  }

  @Test
  void rejectsReservedAndMalformedNames() {
    assertThat(ManifestValidator.problems(manifest("registry", "oriso-addon/v1", "/ui/e.js", List.of(app("a", "admin.page")))))
        .anyMatch(p -> p.contains("reserved"));
    assertThat(ManifestValidator.problems(manifest("Bad_Name", "oriso-addon/v1", "/ui/e.js", List.of(app("a", "admin.page")))))
        .anyMatch(p -> p.startsWith("name"));
  }

  @Test
  void rejectsUnknownContractSlotAndUiOutsideUi() {
    var problems =
        ManifestValidator.problems(manifest("demo", "oriso-addon/v2", "/assets/e.js", List.of(app("a", "chat.sidebar"))));
    assertThat(problems)
        .anyMatch(p -> p.startsWith("contract"))
        .anyMatch(p -> p.startsWith("ui"))
        .anyMatch(p -> p.contains("unknown slot 'chat.sidebar'"));
  }

  @Test
  void requiresAppsWithSlotsAndUniqueIds() {
    assertThat(ManifestValidator.problems(manifest("demo", "oriso-addon/v1", "/ui/e.js", List.of())))
        .contains("at least one app is required");
    assertThat(ManifestValidator.problems(manifest("demo", "oriso-addon/v1", "/ui/e.js", List.of(app("a"), app("a", "admin.page")))))
        .anyMatch(p -> p.contains("declares no slot"))
        .anyMatch(p -> p.contains("used twice"));
  }

  @Test
  void acceptsAnOptionalCalendarPath() {
    var apps = List.of(app("goals", "asker.navigation"));
    assertThat(ManifestValidator.problems(withCalendar("demo", "oriso-addon/v1", "/ui/e.js", apps, "/calendar"))).isEmpty();
    assertThat(ManifestValidator.problems(withCalendar("demo", "oriso-addon/v1", "/ui/e.js", apps, "/api/v1/calendar"))).isEmpty();
    for (String invalid : List.of("calendar", "/../calendar", "/calendar?x=1", "http://evil/calendar", "")) {
      assertThat(ManifestValidator.problems(withCalendar("demo", "oriso-addon/v1", "/ui/e.js", apps, invalid)))
          .as(invalid)
          .anyMatch(p -> p.startsWith("calendar"));
    }
  }

  @Test
  void reservesTheNamesOfTheHostApiAndCalendar() {
    var apps = List.of(app("goals", "asker.navigation"));
    assertThat(ManifestValidator.problems(manifest("host", "oriso-addon/v1", "/ui/e.js", apps))).anyMatch(p -> p.contains("reserved"));
    assertThat(ManifestValidator.problems(manifest("calendar", "oriso-addon/v1", "/ui/e.js", apps))).anyMatch(p -> p.contains("reserved"));
  }

  @Test
  void acceptsAReadmeOnlyAsHttpsUrlOnAnAllowedHost() {
    String github = "https://raw.githubusercontent.com/BITS-GmbH/oriso-addon-digisucht-tools/main/docs/README.md";
    assertThat(ManifestValidator.problems(withReadme(github))).isEmpty();
    assertThat(ManifestValidator.problems(withReadme(null))).isEmpty();
    for (String invalid :
        List.of(
            "http://raw.githubusercontent.com/a/b/README.md",
            "https://evil.example/README.md",
            "https://user@raw.githubusercontent.com/README.md",
            "https://raw.githubusercontent.com:8443/README.md",
            "/docs/README.md",
            "https://raw.githubusercontent.com/a b",
            "")) {
      assertThat(ManifestValidator.problems(withReadme(invalid))).as(invalid).anyMatch(p -> p.startsWith("readme"));
    }
    assertThat(ManifestValidator.problems(withReadme("https://docs.example/README.md"), List.of("docs.example")))
        .isEmpty();
  }

  private static AddonManifest withScreenshots(AddonManifest.Screenshot... screenshots) {
    var app =
        new AddonManifest.App(
            "goals", Map.of("de", "Ziele"), List.of("asker.navigation", "consultant.session"), null, List.of(screenshots));
    return manifest("demo", "oriso-addon/v1", "/ui/e.js", List.of(app));
  }

  private static AddonManifest.Screenshot screenshot(String slot, String url) {
    return new AddonManifest.Screenshot(slot, url, Map.of("de", "Bild", "en", "Image"));
  }

  @Test
  void acceptsScreenshotsOfTheAppsSlotsOnAnAllowedHost() {
    String url = "https://raw.githubusercontent.com/BITS-GmbH/oriso-addon-digisucht-tools/main/docs/goals.png";
    assertThat(ManifestValidator.problems(withScreenshots())).isEmpty();
    assertThat(
            ManifestValidator.problems(
                withScreenshots(
                    screenshot("asker.navigation", url),
                    new AddonManifest.Screenshot("consultant.session", url, null))))
        .isEmpty();
    assertThat(ManifestValidator.problems(withScreenshots(screenshot("admin.page", url))))
        .anyMatch(p -> p.contains("screenshot slot"));
    assertThat(ManifestValidator.problems(withScreenshots(screenshot(null, url))))
        .anyMatch(p -> p.contains("screenshot slot"));
    for (String invalid :
        Arrays.asList(
            "http://raw.githubusercontent.com/a/b.png",
            "https://evil.example/b.png",
            "https://user@raw.githubusercontent.com/b.png",
            "data:image/png;base64,AAAA",
            "/docs/b.png",
            "",
            null)) {
      assertThat(ManifestValidator.problems(withScreenshots(screenshot("asker.navigation", invalid))))
          .as(String.valueOf(invalid))
          .anyMatch(p -> p.contains("screenshot url"));
    }
    var onDocsHost = withScreenshots(screenshot("asker.navigation", "https://docs.example/b.png"));
    assertThat(ManifestValidator.problems(onDocsHost, List.of("docs.example"))).isEmpty();
  }

  @Test
  void acceptsAtMostSixScreenshotsPerApp() {
    var shot = screenshot("asker.navigation", "https://raw.githubusercontent.com/a/b.png");
    assertThat(ManifestValidator.problems(withScreenshots(shot, shot, shot, shot, shot, shot))).isEmpty();
    assertThat(ManifestValidator.problems(withScreenshots(shot, shot, shot, shot, shot, shot, shot)))
        .anyMatch(p -> p.contains("more than 6 screenshots"));
  }

  @Test
  void acceptsTheSessionMenusAndOneAgencyFinderPerAddon() {
    var apps =
        List.of(
            app("menu", "asker.session-menu", "consultant.session-menu"),
            app("finder", "registration.agency-finder", "asker.navigation"));
    assertThat(ManifestValidator.problems(manifest("demo", "oriso-addon/v1", "/ui/e.js", apps))).isEmpty();

    var twoFinders = List.of(app("a", "registration.agency-finder"), app("b", "registration.agency-finder"));
    assertThat(ManifestValidator.problems(manifest("demo", "oriso-addon/v1", "/ui/e.js", twoFinders)))
        .containsExactly("only one app may use slot 'registration.agency-finder'");
  }

  @Test
  void reservesThePublicName() {
    var apps = List.of(app("goals", "asker.navigation"));
    assertThat(ManifestValidator.problems(manifest("public", "oriso-addon/v1", "/ui/e.js", apps)))
        .anyMatch(p -> p.contains("reserved"));
  }

  private static AddonManifest withIcon(String icon) {
    var app = new AddonManifest.App("goals", Map.of("de", "Ziele"), List.of("asker.navigation"), null, null, icon);
    return manifest("demo", "oriso-addon/v1", "/ui/e.js", List.of(app));
  }

  @Test
  void acceptsAnSvgIconBelowUi() {
    assertThat(ManifestValidator.problems(withIcon(null))).isEmpty();
    assertThat(ManifestValidator.problems(withIcon("/ui/icons/goals.svg"))).isEmpty();
    for (String invalid :
        List.of(
            "/ui/icons/goals.png",
            "/icons/goals.svg",
            "/ui/../api/goals.svg",
            "/ui/icons/..svg",
            "/ui//goals.svg",
            "https://evil.example/ui/goals.svg",
            "/ui/goals.svg?x=1",
            "/ui/" + "a".repeat(200) + ".svg",
            "")) {
      assertThat(ManifestValidator.problems(withIcon(invalid))).as(invalid).anyMatch(p -> p.contains("icon"));
    }
  }

  @Test
  void exposesIconsBelowThePublicPath() {
    var manifest = withIcon("/ui/icons/goals.svg").withPublicIcons("/service/addons");
    assertThat(manifest.apps().getFirst().icon()).isEqualTo("/service/addons/demo/ui/icons/goals.svg");
  }
}
