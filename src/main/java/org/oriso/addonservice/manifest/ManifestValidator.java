package org.oriso.addonservice.manifest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class ManifestValidator {

  private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{1,40}$");
  private static final Pattern APP_ID = Pattern.compile("^[a-z][a-z0-9-]{0,40}$");
  private static final Pattern PATH = Pattern.compile("^(/[A-Za-z0-9._-]+)+$");

  /** Names that would collide with the AddonService's own routes under the public path. */
  private static final Set<String> RESERVED = Set.of("registry", "admin", "well-known", "host", "calendar");

  private ManifestValidator() {}

  public static List<String> problems(AddonManifest manifest) {
    var problems = new ArrayList<String>();
    if (manifest.name() == null || !NAME.matcher(manifest.name()).matches()) {
      problems.add("name must match " + NAME.pattern());
    } else if (RESERVED.contains(manifest.name())) {
      problems.add("name '" + manifest.name() + "' is reserved");
    }
    if (!AddonManifest.CONTRACT_V1.equals(manifest.contract())) {
      problems.add("contract must be " + AddonManifest.CONTRACT_V1);
    }
    if (manifest.version() == null || manifest.version().isBlank()) {
      problems.add("version is missing");
    }
    if (manifest.ui() == null || !manifest.ui().startsWith("/ui/")) {
      problems.add("ui must be a path below /ui/");
    }
    if (manifest.calendar() != null
        && (!PATH.matcher(manifest.calendar()).matches() || manifest.calendar().contains(".."))) {
      problems.add("calendar must be a plain path such as /calendar");
    }
    if (manifest.apps().isEmpty()) {
      problems.add("at least one app is required");
    }
    Set<String> appIds = new HashSet<>();
    for (var app : manifest.apps()) {
      if (app.id() == null || !APP_ID.matcher(app.id()).matches()) {
        problems.add("app id must match " + APP_ID.pattern());
      } else if (!appIds.add(app.id())) {
        problems.add("app id '" + app.id() + "' is used twice");
      }
      if (app.slots().isEmpty()) {
        problems.add("app '" + app.id() + "' declares no slot");
      }
      for (var slot : app.slots()) {
        if (Slot.fromId(slot).isEmpty()) {
          problems.add("app '" + app.id() + "' uses unknown slot '" + slot + "'");
        }
      }
    }
    return problems;
  }
}
