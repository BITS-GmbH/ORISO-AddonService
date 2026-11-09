package org.oriso.addonservice.enablement;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddonEnablementRepository extends JpaRepository<AddonEnablement, Long> {

  Optional<AddonEnablement> findByAddonNameAndTenantIdAndAgencyIdIsNull(String addonName, Long tenantId);

  Optional<AddonEnablement> findByAddonNameAndTenantIdAndAgencyId(
      String addonName, Long tenantId, Long agencyId);

  List<AddonEnablement> findByTenantId(Long tenantId);

  List<AddonEnablement> findByAddonName(String addonName);
}
