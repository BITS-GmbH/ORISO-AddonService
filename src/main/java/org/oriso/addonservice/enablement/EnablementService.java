package org.oriso.addonservice.enablement;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Three levels, each a complete off switch for the ones below it: an add-on is off until a
 * SuperAdmin switches it on globally and for a Träger. Within an enabled Träger, single agencies
 * can be switched off (or explicitly on again). Without an enabled Träger, an agency switch has no
 * effect; without the global switch, neither has.
 */
@Service
public class EnablementService {

  private final AddonEnablementRepository repository;
  private final AddonGlobalSwitchRepository globalSwitches;
  private final Clock clock;

  public EnablementService(
      AddonEnablementRepository repository, AddonGlobalSwitchRepository globalSwitches, Clock clock) {
    this.repository = repository;
    this.globalSwitches = globalSwitches;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public boolean isGloballyEnabled(String addon) {
    return globalSwitches.findById(addon).map(AddonGlobalSwitch::isEnabled).orElse(false);
  }

  @Transactional(readOnly = true)
  public Optional<AddonGlobalSwitch> globalSwitch(String addon) {
    return globalSwitches.findById(addon);
  }

  @Transactional
  public AddonGlobalSwitch setGlobal(String addon, boolean enabled, String by) {
    var row = globalSwitches.findById(addon).orElseGet(() -> new AddonGlobalSwitch(addon));
    row.set(enabled, clock.instant(), by);
    return globalSwitches.save(row);
  }

  @Transactional(readOnly = true)
  public boolean isEnabled(String addon, Long tenantId, Long agencyId) {
    if (tenantId == null || !isGloballyEnabled(addon)) {
      return false;
    }
    boolean tenantEnabled =
        repository
            .findByAddonNameAndTenantIdAndAgencyIdIsNull(addon, tenantId)
            .map(AddonEnablement::isEnabled)
            .orElse(false);
    if (!tenantEnabled || agencyId == null) {
      return tenantEnabled;
    }
    return repository
        .findByAddonNameAndTenantIdAndAgencyId(addon, tenantId, agencyId)
        .map(AddonEnablement::isEnabled)
        .orElse(true);
  }

  @Transactional
  public AddonEnablement setForTenant(String addon, long tenantId, boolean enabled, String by) {
    var row =
        repository
            .findByAddonNameAndTenantIdAndAgencyIdIsNull(addon, tenantId)
            .orElseGet(() -> new AddonEnablement(addon, tenantId, null));
    row.set(enabled, clock.instant(), by);
    return repository.save(row);
  }

  @Transactional
  public AddonEnablement setForAgency(
      String addon, long tenantId, long agencyId, boolean enabled, String by) {
    var row =
        repository
            .findByAddonNameAndTenantIdAndAgencyId(addon, tenantId, agencyId)
            .orElseGet(() -> new AddonEnablement(addon, tenantId, agencyId));
    row.set(enabled, clock.instant(), by);
    return repository.save(row);
  }

  @Transactional
  public void clearAgencyOverride(String addon, long tenantId, long agencyId) {
    repository
        .findByAddonNameAndTenantIdAndAgencyId(addon, tenantId, agencyId)
        .ifPresent(repository::delete);
  }

  @Transactional(readOnly = true)
  public List<AddonEnablement> forTenant(long tenantId) {
    return repository.findByTenantId(tenantId);
  }

  @Transactional(readOnly = true)
  public List<AddonEnablement> forAddon(String addon) {
    return repository.findByAddonName(addon);
  }
}
