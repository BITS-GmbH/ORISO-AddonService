package org.oriso.addonservice.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Plain text per locale, as add-ons send it for titles and summaries; {@code de} is required. */
public final class LocalizedText {

  private static final Pattern LOCALE = Pattern.compile("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$");
  private static final int MAX_LOCALES = 20;

  private LocalizedText() {}

  public static List<String> problems(String field, Map<String, String> text, int maxLength) {
    var problems = new ArrayList<String>();
    if (text == null || text.get("de") == null || text.get("de").isBlank()) {
      problems.add(field + ".de is required");
      return problems;
    }
    if (text.size() > MAX_LOCALES) {
      problems.add(field + " has more than " + MAX_LOCALES + " locales");
    }
    text.forEach(
        (locale, value) -> {
          if (locale == null || !LOCALE.matcher(locale).matches()) {
            problems.add(field + " has an invalid locale '" + locale + "'");
          } else if (value == null || value.isBlank()) {
            problems.add(field + "." + locale + " is empty");
          } else if (value.length() > maxLength) {
            problems.add(field + "." + locale + " is longer than " + maxLength + " characters");
          }
        });
    return problems;
  }
}
