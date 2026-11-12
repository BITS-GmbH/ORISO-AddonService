package org.oriso.addonservice.web;

import java.time.Duration;
import java.time.Instant;
import org.oriso.addonservice.calendar.CalendarService;
import org.oriso.addonservice.config.AddonProperties;
import org.oriso.addonservice.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Calendar entries of the caller's add-ons, for the web app's appointment views. Read only. */
@RestController
public class CalendarController {

  private final CalendarService calendar;
  private final AddonAccess access;
  private final Duration maxRange;

  public CalendarController(CalendarService calendar, AddonAccess access, AddonProperties properties) {
    this.calendar = calendar;
    this.access = access;
    this.maxRange = properties.calendar().maxRange();
  }

  @GetMapping("/addons/calendar")
  public CalendarService.Calendar entries(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestHeader(value = AddonAccess.AGENCY_HEADER, required = false) String agencyHeader) {
    if (!from.isBefore(to)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
    }
    if (Duration.between(from, to).compareTo(maxRange) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "The range must not exceed " + maxRange.toDays() + " days");
    }
    Caller caller = Caller.from(jwt);
    return calendar.collect(caller, access.agencyContext(caller, agencyHeader), from, to);
  }
}
