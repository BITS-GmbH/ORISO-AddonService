package org.oriso.addonservice.enablement;

import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An add-on is off until a SuperAdmin switches it on for a Träger. Within an enabled Träger,
 * single agencies can be switched off (or explicitly on again). Without an enabled Träger, an
 * agency switch has no effect.
 */
@Service
public class EnablementService {

  private final AddonEnablementRepository repository;
  private final Clock clock;

  public EnablementService(AddonEnablementRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public boolean isEnabled(String addon, Long tenantId, Long agencyId) {
    if (tenantId == null) {
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
