package org.oriso.addonservice.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManifestValidatorTest {

  private static AddonManifest manifest(String name, String contract, String ui, List<AddonManifest.App> apps) {
    return new AddonManifest(name, "1.0.0", contract, "^19", apps, ui, null, null, null, null, "/health");
  }

  private static AddonManifest.App app(String id, String... slots) {
    return new AddonManifest.App(id, Map.of("de", "Titel"), List.of(slots));
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
}
