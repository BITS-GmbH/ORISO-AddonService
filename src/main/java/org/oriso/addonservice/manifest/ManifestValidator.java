package org.oriso.addonservice.manifest;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class ManifestValidator {

  private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{1,40}$");
  private static final Pattern APP_ID = Pattern.compile("^[a-z][a-z0-9-]{0,40}$");
  private static final Pattern PATH = Pattern.compile("^(/[A-Za-z0-9._-]+)+$");
  private static final Pattern ICON = Pattern.compile("^/ui(/[A-Za-z0-9._-]+)+\\.svg$");
  private static final int MAX_ICON_LENGTH = 200;

  /** Names that would collide with the AddonService's own routes under the public path. */
  private static final Set<String> RESERVED = Set.of("registry", "admin", "well-known", "host", "calendar", "public");

  public static final int MAX_SCREENSHOTS_PER_APP = 6;

  public static final Set<String> DEFAULT_README_HOSTS = Set.of("raw.githubusercontent.com");

  private ManifestValidator() {}

  public static List<String> problems(AddonManifest manifest) {
    return problems(manifest, DEFAULT_README_HOSTS);
  }

  public static List<String> problems(AddonManifest manifest, Collection<String> readmeHosts) {
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
    if (manifest.readme() != null && !isAllowedUrl(manifest.readme(), readmeHosts)) {
      problems.add("readme must be an https URL on " + String.join(", ", readmeHosts));
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
      if (app.visibility() != null
          && !AddonManifest.App.STATIC.equals(app.visibility())
          && !AddonManifest.App.DYNAMIC.equals(app.visibility())) {
        problems.add("app '" + app.id() + "' visibility must be 'static' or 'dynamic'");
      }
      if (app.slots().isEmpty()) {
        problems.add("app '" + app.id() + "' declares no slot");
      }
      for (var slot : app.slots()) {
        if (Slot.fromId(slot).isEmpty()) {
          problems.add("app '" + app.id() + "' uses unknown slot '" + slot + "'");
        }
      }
      if (app.icon() != null
          && (app.icon().length() > MAX_ICON_LENGTH || !ICON.matcher(app.icon()).matches() || app.icon().contains(".."))) {
        problems.add("app '" + app.id() + "' icon must be an .svg path below /ui/ of at most " + MAX_ICON_LENGTH + " characters");
      }
      screenshotProblems(app, readmeHosts, problems);
    }
    long agencyFinders =
        manifest.apps().stream().filter(app -> app.slots().contains(Slot.REGISTRATION_AGENCY_FINDER.id())).count();
    if (agencyFinders > 1) {
      problems.add("only one app may use slot '" + Slot.REGISTRATION_AGENCY_FINDER.id() + "'");
    }
    return problems;
  }

  private static void screenshotProblems(AddonManifest.App app, Collection<String> hosts, List<String> problems) {
    if (app.screenshots().size() > MAX_SCREENSHOTS_PER_APP) {
      problems.add("app '" + app.id() + "' has more than " + MAX_SCREENSHOTS_PER_APP + " screenshots");
    }
    for (var screenshot : app.screenshots()) {
      if (screenshot == null) {
        problems.add("app '" + app.id() + "' has an empty screenshot");
        continue;
      }
      if (screenshot.slot() == null || !app.slots().contains(screenshot.slot())) {
        problems.add("app '" + app.id() + "' screenshot slot must be one of its slots");
      }
      if (!isAllowedUrl(screenshot.url(), hosts)) {
        problems.add("app '" + app.id() + "' screenshot url must be an https URL on " + String.join(", ", hosts));
      }
    }
  }

  private static boolean isAllowedUrl(String url, Collection<String> hosts) {
    if (url == null) {
      return false;
    }
    URI uri;
    try {
      uri = new URI(url);
    } catch (Exception e) {
      return false;
    }
    return "https".equals(uri.getScheme())
        && uri.getRawUserInfo() == null
        && uri.getHost() != null
        && uri.getPort() == -1
        && hosts.stream().anyMatch(host -> host.toLowerCase(Locale.ROOT).equals(uri.getHost().toLowerCase(Locale.ROOT)));
  }
}
