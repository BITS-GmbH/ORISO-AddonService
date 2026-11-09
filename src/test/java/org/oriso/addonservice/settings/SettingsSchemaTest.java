package org.oriso.addonservice.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SettingsSchemaTest {

  private final SettingsSchema schema =
      new SettingsSchema(
          Map.of(
              "type", "object",
              "required", List.of("intro"),
              "properties",
                  Map.of(
                      "intro", Map.of("type", "string", "maxLength", 5, "default", "Hi"),
                      "mode", Map.of("type", "string", "enum", List.of("a", "b")),
                      "on", Map.of("type", "boolean", "default", false),
                      "limit", Map.of("type", "integer", "minimum", 1, "maximum", 3))));

  @Test
  void collectsDefaults() {
    assertThat(schema.defaults()).containsEntry("intro", "Hi").containsEntry("on", false).doesNotContainKey("limit");
  }

  @Test
  void acceptsValidValues() {
    assertThat(schema.problems(Map.of("intro", "Hallo", "mode", "b", "on", true, "limit", 2))).isEmpty();
  }

  @Test
  void reportsEveryKindOfProblem() {
    assertThat(schema.problems(Map.of("intro", "zu lang", "mode", "c", "on", "yes", "limit", 2.5, "other", 1)))
        .contains(
            "intro: at most 5 characters",
            "mode: not one of the allowed values",
            "on: must be true or false",
            "limit: must be a whole number",
            "other: unknown setting");
    assertThat(schema.problems(Map.of("limit", 9))).contains("intro: required", "limit: at most 3");
  }
}
