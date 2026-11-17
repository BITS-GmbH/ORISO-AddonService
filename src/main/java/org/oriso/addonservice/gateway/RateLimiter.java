package org.oriso.addonservice.gateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.oriso.addonservice.config.AddonProperties;
import org.springframework.stereotype.Component;

/**
 * Requests per minute and key (client address and add-on) for the public gateway, in memory and
 * per replica. A fixed window is enough to stop a visitor from hammering an add-on.
 */
@Component
public class RateLimiter {

  private static final Duration WINDOW = Duration.ofMinutes(1);
  private static final int MAX_KEYS = 100_000;

  private final int limit;
  private final Clock clock;
  private final Map<String, Window> windows = new ConcurrentHashMap<>();

  public RateLimiter(AddonProperties properties, Clock clock) {
    this.limit = properties.publicGateway().requestsPerMinute();
    this.clock = clock;
  }

  public boolean tryAcquire(String key) {
    Instant now = clock.instant();
    if (windows.size() >= MAX_KEYS) {
      windows.values().removeIf(window -> window.isOver(now));
    }
    Window window =
        windows.compute(
            key, (k, current) -> current == null || current.isOver(now) ? new Window(now, 1) : current.next());
    return window.count() <= limit;
  }

  private record Window(Instant start, int count) {

    boolean isOver(Instant now) {
      return !now.isBefore(start.plus(WINDOW));
    }

    Window next() {
      return new Window(start, count == Integer.MAX_VALUE ? count : count + 1);
    }
  }
}
