package org.oriso.addonservice.settings;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddonSettingRepository extends JpaRepository<AddonSetting, Long> {

  Optional<AddonSetting> findByAddonNameAndTenantIdAndAgencyIdIsNull(String addonName, Long tenantId);

  Optional<AddonSetting> findByAddonNameAndTenantIdAndAgencyId(String addonName, Long tenantId, Long agencyId);
}
