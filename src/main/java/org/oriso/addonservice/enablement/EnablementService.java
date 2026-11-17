package org.oriso.addonservice.enablement;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Three levels, each a complete off switch for the ones below it: an add-on is off until a
 * SuperAdmin switches it on globally and gives a Träger a mode other than {@link TenantMode#OFF}.
 * In {@link TenantMode#TENANT} the Träger's own switch decides. Within a Träger that is on, single
 * agencies can be switched off (or explicitly on again), except in {@link TenantMode#ON_HIDDEN}.
 * Without the Träger on, an agency switch has no effect; without the global switch, neither has.
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
    var tenantSwitch = repository.findByAddonNameAndTenantIdAndAgencyIdIsNull(addon, tenantId);
    boolean tenantOn = tenantSwitch.map(AddonEnablement::isOnForTenant).orElse(false);
    if (!tenantOn || agencyId == null || tenantSwitch.get().getMode() == TenantMode.ON_HIDDEN) {
      return tenantOn;
    }
    return repository
        .findByAddonNameAndTenantIdAndAgencyId(addon, tenantId, agencyId)
        .map(AddonEnablement::isEnabled)
        .orElse(true);
  }

  @Transactional(readOnly = true)
  public TenantMode tenantMode(String addon, long tenantId) {
    return repository
        .findByAddonNameAndTenantIdAndAgencyIdIsNull(addon, tenantId)
        .map(AddonEnablement::getMode)
        .orElse(TenantMode.OFF);
  }

  /** Whether the admins of a Träger see the add-on: on globally and in mode TENANT or ON. */
  @Transactional(readOnly = true)
  public boolean isVisibleToTenantAdmins(String addon, long tenantId) {
    return isGloballyEnabled(addon) && tenantMode(addon, tenantId).isVisibleToTenantAdmins();
  }

  /**
   * The Träger switch. In mode TENANT it sets the Träger's own flag; in the other modes (only
   * SuperAdmins get there) on means ON, or stays ON_HIDDEN, and off means OFF.
   */
  @Transactional
  public AddonEnablement setForTenant(String addon, long tenantId, boolean enabled, String by) {
    var row = tenantRow(addon, tenantId);
    TenantMode current = row.getId() == null ? TenantMode.OFF : row.getMode();
    row.set(modeAfterSwitch(current, enabled), enabled, clock.instant(), by);
    return repository.save(row);
  }

  private static TenantMode modeAfterSwitch(TenantMode current, boolean enabled) {
    if (current == TenantMode.TENANT) {
      return TenantMode.TENANT;
    }
    if (!enabled) {
      return TenantMode.OFF;
    }
    return current == TenantMode.ON_HIDDEN ? TenantMode.ON_HIDDEN : TenantMode.ON;
  }

  /** Sets the mode; switching to TENANT keeps whether the add-on is on for the Träger now. */
  @Transactional
  public AddonEnablement setMode(String addon, long tenantId, TenantMode mode, String by) {
    var row = tenantRow(addon, tenantId);
    boolean enabled =
        switch (mode) {
          case OFF -> false;
          case ON, ON_HIDDEN -> true;
          case TENANT -> row.getId() != null && row.isOnForTenant();
        };
    row.set(mode, enabled, clock.instant(), by);
    return repository.save(row);
  }

  private AddonEnablement tenantRow(String addon, long tenantId) {
    return repository
        .findByAddonNameAndTenantIdAndAgencyIdIsNull(addon, tenantId)
        .orElseGet(() -> new AddonEnablement(addon, tenantId, null));
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
