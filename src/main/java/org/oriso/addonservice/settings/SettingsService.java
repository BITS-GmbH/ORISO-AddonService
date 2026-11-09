package org.oriso.addonservice.settings;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.oriso.addonservice.registry.AddonClient;
import org.oriso.addonservice.registry.RegisteredAddon;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Settings are stored per Träger and per agency. What an add-on sees is: the schema defaults,
 * overridden by the Träger's values, overridden by the agency's values.
 */
@Service
public class SettingsService {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final AddonSettingRepository repository;
  private final AddonClient addons;
  private final JsonMapper json;
  private final Clock clock;

  public SettingsService(AddonSettingRepository repository, AddonClient addons, JsonMapper json, Clock clock) {
    this.repository = repository;
    this.addons = addons;
    this.json = json;
    this.clock = clock;
  }

  public SettingsSchema schema(RegisteredAddon addon) {
    return new SettingsSchema(addons.settingsSchema(addon).orElse(null));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> stored(String addon, long tenantId, Long agencyId) {
    var row =
        agencyId == null
            ? repository.findByAddonNameAndTenantIdAndAgencyIdIsNull(addon, tenantId)
            : repository.findByAddonNameAndTenantIdAndAgencyId(addon, tenantId, agencyId);
    return row.map(setting -> json.readValue(setting.getValuesJson(), MAP)).orElse(Map.of());
  }

  @Transactional(readOnly = true)
  public Map<String, Object> effective(RegisteredAddon addon, long tenantId, Long agencyId) {
    var values = new LinkedHashMap<>(schema(addon).defaults());
    values.putAll(stored(addon.name(), tenantId, null));
    if (agencyId != null) {
      values.putAll(stored(addon.name(), tenantId, agencyId));
    }
    return values;
  }

  @Transactional
  public Map<String, Object> save(
      RegisteredAddon addon, long tenantId, Long agencyId, Map<String, Object> values, String by) {
    List<String> problems = schema(addon).problems(values);
    if (!problems.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", problems));
    }
    var row =
        (agencyId == null
                ? repository.findByAddonNameAndTenantIdAndAgencyIdIsNull(addon.name(), tenantId)
                : repository.findByAddonNameAndTenantIdAndAgencyId(addon.name(), tenantId, agencyId))
            .orElseGet(() -> new AddonSetting(addon.name(), tenantId, agencyId));
    row.set(json.writeValueAsString(values), clock.instant(), by);
    repository.save(row);
    return values;
  }
}
