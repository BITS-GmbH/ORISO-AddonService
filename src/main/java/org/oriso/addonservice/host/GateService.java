package org.oriso.addonservice.host;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.oriso.addonservice.config.AddonProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The core gates an add-on may want to respect, for the Träger of its token. Today the DPA gate
 * of the TenantService: whether the Träger confirmed the current data processing agreement and
 * whether new counselling is allowed. The add-on decides what a closed gate means for it.
 */
@Service
public class GateService {

  private final AddonProperties.TenantService properties;
  private final CoreReadClient core;
  private final Clock clock;
  private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

  public GateService(AddonProperties properties, CoreReadClient core, Clock clock) {
    this.properties = properties.tenantService();
    this.core = core;
    this.clock = clock;
  }

  public Map<String, Object> gates(long tenantId) {
    Instant now = clock.instant();
    Cached cached = cache.get(tenantId);
    if (cached != null && now.isBefore(cached.until())) {
      return cached.gates();
    }
    var uri =
        UriComponentsBuilder.fromUri(properties.baseUrl())
            .path(properties.dpaGatePath())
            .buildAndExpand(tenantId)
            .toUri();
    Map<String, Object> dpa = core.get(uri, "DPA gate of Träger " + tenantId);
    var gate = new LinkedHashMap<String, Object>();
    gate.put("status", dpa.get("dpaStatus"));
    gate.put("signed", dpa.get("dpaSigned"));
    gate.put("newCounsellingAllowed", dpa.get("newCounsellingAllowed"));
    gate.put("renewalGraceActive", dpa.get("renewalGraceActive"));
    gate.put("signingDeadlineAt", dpa.get("signingDeadlineAt"));
    Map<String, Object> gates = Map.of("dpa", gate);
    cache.put(tenantId, new Cached(gates, now.plus(properties.cacheTtl())));
    return gates;
  }

  private record Cached(Map<String, Object> gates, Instant until) {}
}
