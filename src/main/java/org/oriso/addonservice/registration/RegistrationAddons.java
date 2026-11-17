package org.oriso.addonservice.registration;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.oriso.addonservice.config.AddonProperties;
import org.oriso.addonservice.enablement.EnablementService;
import org.oriso.addonservice.manifest.AddonManifest;
import org.oriso.addonservice.manifest.Slot;
import org.oriso.addonservice.registry.AddonRegistry;
import org.oriso.addonservice.registry.RegisteredAddon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The add-ons that may take over the agency selection of the registration for a Träger: an app in
 * {@code registration.agency-finder} and on at Träger level. Agency overrides do not apply, the
 * visitor has no agency yet. Cached briefly per Träger, since anyone can ask.
 */
@Service
public class RegistrationAddons {

  private static final Logger log = LoggerFactory.getLogger(RegistrationAddons.class);
  private static final int MAX_CACHED_TENANTS = 1000;

  private final AddonRegistry registry;
  private final EnablementService enablement;
  private final AddonProperties.PublicGateway properties;
  private final Clock clock;
  private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

  public RegistrationAddons(
      AddonRegistry registry, EnablementService enablement, AddonProperties properties, Clock clock) {
    this.registry = registry;
    this.enablement = enablement;
    this.properties = properties.publicGateway();
    this.clock = clock;
  }

  /** The add-on the registration shows; with several, the first by name. */
  public Optional<AgencyFinder> selected(long tenantId) {
    List<AgencyFinder> finders = forTenant(tenantId);
    if (finders.size() > 1) {
      log.warn(
          "Several add-ons offer {} for Träger {}: {}; using {}",
          Slot.REGISTRATION_AGENCY_FINDER.id(),
          tenantId,
          finders.stream().map(finder -> finder.addon().name()).toList(),
          finders.getFirst().addon().name());
    }
    return finders.stream().findFirst();
  }

  public boolean isAllowed(String addon, long tenantId) {
    return forTenant(tenantId).stream().anyMatch(finder -> finder.addon().name().equals(addon));
  }

  private List<AgencyFinder> forTenant(long tenantId) {
    Instant now = clock.instant();
    Cached cached = cache.get(tenantId);
    if (cached != null && now.isBefore(cached.until())) {
      return cached.finders();
    }
    List<AgencyFinder> finders =
        registry.all().stream()
            .flatMap(addon -> agencyFinderApp(addon.manifest()).map(app -> new AgencyFinder(addon, app)).stream())
            .filter(finder -> enablement.isEnabled(finder.addon().name(), tenantId, null))
            .toList();
    if (cache.size() >= MAX_CACHED_TENANTS) {
      cache.values().removeIf(entry -> !now.isBefore(entry.until()));
    }
    if (cache.size() < MAX_CACHED_TENANTS) {
      cache.put(tenantId, new Cached(finders, now.plus(properties.cacheTtl())));
    }
    return finders;
  }

  private static Optional<AddonManifest.App> agencyFinderApp(AddonManifest manifest) {
    return manifest.apps().stream()
        .filter(app -> app.slots().contains(Slot.REGISTRATION_AGENCY_FINDER.id()))
        .findFirst();
  }

  public record AgencyFinder(RegisteredAddon addon, AddonManifest.App app) {}

  private record Cached(List<AgencyFinder> finders, Instant until) {}
}
