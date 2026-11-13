package org.oriso.addonservice.lifecycle;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventDeliveryRepository extends JpaRepository<EventDelivery, Long> {

  boolean existsByEventIdAndAddonName(String eventId, String addonName);

  @Query(
      "select d from EventDelivery d where d.deliveredAt is null and d.failedAt is null"
          + " and d.nextAttemptAt <= :now order by d.nextAttemptAt")
  List<EventDelivery> findDue(@Param("now") Instant now, Pageable page);

  List<EventDelivery> findByEventId(String eventId);
}
