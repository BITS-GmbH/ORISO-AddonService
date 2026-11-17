package org.oriso.addonservice.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.oriso.addonservice.config.AddonProperties;
import org.springframework.amqp.core.Binding;

class LifecycleListenerTest {

  @Test
  void bindsTheQueueToEveryContractEventType() {
    var lifecycle =
        new AddonProperties.Lifecycle(
            true, "oriso.lifecycle", "addonservice.lifecycle", "/events", Duration.ofSeconds(10),
            Duration.ofSeconds(30), Duration.ofHours(6), Duration.ofDays(7), 50);
    var properties =
        new AddonProperties("/service/addons", null, null, null, null, null, null, null, null, lifecycle, null, null);

    var bindings = new LifecycleListener(null).lifecycleTopology(properties).getDeclarablesByType(Binding.class);

    assertThat(bindings).allMatch(binding -> binding.getExchange().equals("oriso.lifecycle"));
    assertThat(bindings.stream().map(Binding::getRoutingKey))
        .containsExactlyInAnyOrder("user.deleted", "session.deleted", "user.registered", "session.interaction");
  }
}
