package org.oriso.addonservice.audit;

import org.springframework.data.repository.Repository;

/** Append only: entries can be added, never changed or removed. */
public interface AddonAuditEntryRepository extends Repository<AddonAuditEntry, Long> {

  AddonAuditEntry save(AddonAuditEntry entry);
}
