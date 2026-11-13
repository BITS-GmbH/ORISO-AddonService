package org.oriso.addonservice.lifecycle;

import java.nio.charset.StandardCharsets;
import org.oriso.addonservice.config.AddonProperties;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Subscribes to the core's lifecycle exchange (topic {@code oriso.lifecycle}, routing keys = event
 * types) with a durable queue of its own, so events published while the AddonService is down are
 * kept. Only active with {@code addons.lifecycle.enabled=true}.
 */
@Configuration
@ConditionalOnProperty(name = "addons.lifecycle.enabled", havingValue = "true")
public class LifecycleListener {

  private final LifecycleEvents events;

  public LifecycleListener(LifecycleEvents events) {
    this.events = events;
  }

  @Bean
  Declarables lifecycleTopology(AddonProperties properties) {
    var lifecycle = properties.lifecycle();
    var exchange = new TopicExchange(lifecycle.exchange(), true, false);
    var queue = new Queue(lifecycle.queue(), true);
    var declarables = new java.util.ArrayList<org.springframework.amqp.core.Declarable>();
    declarables.add(exchange);
    declarables.add(queue);
    LifecycleEvents.TYPES.forEach(type -> declarables.add(BindingBuilder.bind(queue).to(exchange).with(type)));
    return new Declarables(declarables);
  }

  @RabbitListener(queues = "${addons.lifecycle.queue:addonservice.lifecycle}")
  public void onEvent(Message message) {
    events.accept(new String(message.getBody(), StandardCharsets.UTF_8));
  }
}
