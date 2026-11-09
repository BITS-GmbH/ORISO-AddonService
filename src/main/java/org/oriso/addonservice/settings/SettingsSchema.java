package org.oriso.addonservice.settings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The part of JSON Schema add-on settings use: an object whose properties are strings (optionally
 * an enum or a length limit), booleans, integers or numbers (optionally a range), with defaults
 * and required fields. Anything else in the schema is ignored.
 */
public final class SettingsSchema {

  private final Map<String, Object> schema;

  public SettingsSchema(Map<String, Object> schema) {
    this.schema = schema == null ? Map.of() : schema;
  }

  public Map<String, Object> raw() {
    return schema;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Map<String, Object>> properties() {
    if (schema.get("properties") instanceof Map<?, ?> properties) {
      var result = new LinkedHashMap<String, Map<String, Object>>();
      properties.forEach(
          (name, definition) -> {
            if (definition instanceof Map<?, ?> map) {
              result.put(String.valueOf(name), (Map<String, Object>) map);
            }
          });
      return result;
    }
    return Map.of();
  }

  public Map<String, Object> defaults() {
    var defaults = new LinkedHashMap<String, Object>();
    properties().forEach(
        (name, definition) -> {
          if (definition.containsKey("default")) {
            defaults.put(name, definition.get("default"));
          }
        });
    return defaults;
  }

  /** Problems with the given values; values for unknown properties are reported too. */
  public List<String> problems(Map<String, Object> values) {
    var problems = new ArrayList<String>();
    var properties = properties();
    for (String name : values.keySet()) {
      if (!properties.containsKey(name)) {
        problems.add(name + ": unknown setting");
      }
    }
    if (schema.get("required") instanceof Collection<?> required) {
      for (Object name : required) {
        Object value = values.get(String.valueOf(name));
        if (value == null || (value instanceof String text && text.isBlank())) {
          problems.add(name + ": required");
        }
      }
    }
    properties.forEach(
        (name, definition) -> {
          Object value = values.get(name);
          if (value != null) {
            check(name, definition, value, problems);
          }
        });
    return problems;
  }

  private static void check(String name, Map<String, Object> definition, Object value, List<String> problems) {
    String type = String.valueOf(definition.get("type"));
    switch (type) {
      case "string" -> {
        if (!(value instanceof String text)) {
          problems.add(name + ": must be text");
          return;
        }
        if (definition.get("maxLength") instanceof Number max && text.length() > max.intValue()) {
          problems.add(name + ": at most " + max.intValue() + " characters");
        }
        if (definition.get("enum") instanceof Collection<?> options && !options.contains(text)) {
          problems.add(name + ": not one of the allowed values");
        }
      }
      case "boolean" -> {
        if (!(value instanceof Boolean)) {
          problems.add(name + ": must be true or false");
        }
      }
      case "integer", "number" -> {
        if (!(value instanceof Number number)
            || ("integer".equals(type) && number.doubleValue() != Math.rint(number.doubleValue()))) {
          problems.add(name + ": must be " + ("integer".equals(type) ? "a whole number" : "a number"));
          return;
        }
        if (definition.get("minimum") instanceof Number min && number.doubleValue() < min.doubleValue()) {
          problems.add(name + ": at least " + min);
        }
        if (definition.get("maximum") instanceof Number max && number.doubleValue() > max.doubleValue()) {
          problems.add(name + ": at most " + max);
        }
      }
      default -> {
        // Types the settings form does not support are stored as given.
      }
    }
  }
}
