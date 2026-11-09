package org.oriso.addonservice.registry;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** The add-ons found in the last discovery round. */
@Component
public class AddonRegistry {

  private final AtomicReference<Map<String, RegisteredAddon>> addons = new AtomicReference<>(Map.of());

  public void replaceAll(Map<String, RegisteredAddon> discovered) {
    addons.set(Map.copyOf(discovered));
  }

  public Optional<RegisteredAddon> find(String name) {
    return Optional.ofNullable(addons.get().get(name));
  }

  public List<RegisteredAddon> all() {
    Collection<RegisteredAddon> values = addons.get().values();
    return values.stream().sorted(Comparator.comparing(RegisteredAddon::name)).toList();
  }
}
