package org.oriso.addonservice.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalizedTextTest {

  @Test
  void requiresGermanAndLimitsLength() {
    assertThat(LocalizedText.problems("title", Map.of("de", "Hallo", "en", "Hello"), 10)).isEmpty();
    assertThat(LocalizedText.problems("title", null, 10)).containsExactly("title.de is required");
    assertThat(LocalizedText.problems("title", Map.of("en", "Hello"), 10)).containsExactly("title.de is required");
    assertThat(LocalizedText.problems("title", Map.of("de", "x".repeat(11)), 10))
        .containsExactly("title.de is longer than 10 characters");
  }

  @Test
  void rejectsInvalidLocalesAndEmptyTexts() {
    assertThat(LocalizedText.problems("text", Map.of("de", "Hallo", "<b>", "x"), 10)).anyMatch(p -> p.contains("invalid locale"));
    assertThat(LocalizedText.problems("text", Map.of("de", "Hallo", "en", " "), 10)).containsExactly("text.en is empty");
  }
}
