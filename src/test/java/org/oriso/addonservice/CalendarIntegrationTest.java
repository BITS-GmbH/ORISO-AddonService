package org.oriso.addonservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.oriso.addonservice.discovery.AddonDiscovery;
import org.oriso.addonservice.enablement.AddonEnablementRepository;
import org.oriso.addonservice.enablement.EnablementService;
import org.oriso.addonservice.security.AgencyMembership;
import org.oriso.addonservice.token.AddonTokenIssuer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CalendarIntegrationTest {

  private static final FakeAddon DEMO = start("demo");
  private static final FakeAddon BROKEN = start("broken");
  private static final FakeAddon SLOW = start("slow");
  private static final FakeAddon DISABLED = start("disabled");

  private static final String RANGE = "from=2026-11-01T00:00:00Z&to=2026-12-01T00:00:00Z";

  @Autowired MockMvc mvc;
  @Autowired AddonDiscovery discovery;
  @Autowired EnablementService enablement;
  @Autowired AddonEnablementRepository enablements;
  @Autowired AddonTokenIssuer issuer;
  @MockitoBean AgencyMembership membership;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add(
        "addons.discovery.static-addons",
        () -> List.of(DEMO, BROKEN, SLOW, DISABLED).stream().map(a -> a.baseUrl().toString()).collect(Collectors.joining(",")));
    registry.add("addons.calendar.timeout", () -> "500ms");
  }

  @AfterAll
  static void stop() {
    List.of(DEMO, BROKEN, SLOW, DISABLED).forEach(FakeAddon::close);
  }

  @BeforeEach
  void setUp() {
    enablements.deleteAll();
    enablement.setGlobal("demo", true, "test");
    enablement.setGlobal("broken", true, "test");
    enablement.setGlobal("slow", true, "test");
    discovery.refresh();
    List.of(DEMO, BROKEN, SLOW, DISABLED).forEach(addon -> addon.lastCalendarRequest.clear());
    enablement.setForTenant("demo", 1L, true, "test");
    DEMO.calendarBody = "{\"entries\":[]}";
    BROKEN.calendarStatus = 500;
    SLOW.calendarDelayMs = 1000;
  }

  private static JwtRequestPostProcessor user(String... roles) {
    return jwt().jwt(
            j ->
                j.subject("user-1")
                    .claim("tenantId", 1L)
                    .claim("realm_access", Map.of("roles", List.of(roles))));
  }

  private static String entry(String id, String appId, String kind, String start, String slot) {
    return """
        { "id": "%s", "appId": "%s", "kind": "%s", "title": { "de": "Termin %s" },
          "start": "%s", "link": { "slot": "%s" } }
        """
        .formatted(id, appId, kind, id, start, slot);
  }

  @Test
  void mergesValidEntriesOfEnabledAddonsSortedByStart() throws Exception {
    DEMO.calendarBody =
        "{\"entries\":["
            + String.join(
                ",",
                entry("late", "client-page", "deadline", "2026-11-20T10:00:00Z", "asker.navigation"),
                entry("early", "client-page", "appointment", "2026-11-03T09:00:00Z", "asker.navigation"),
                entry("bad-kind", "client-page", "meeting", "2026-11-04T09:00:00Z", "asker.navigation"),
                entry("bad-app", "unknown", "reminder", "2026-11-04T09:00:00Z", "asker.navigation"),
                entry("bad-slot", "case-panel", "reminder", "2026-11-04T09:00:00Z", "consultant.session"),
                entry("bad-start", "client-page", "reminder", "tomorrow", "asker.navigation"),
                entry("outside", "client-page", "reminder", "2027-01-04T09:00:00Z", "asker.navigation"))
            + "]}";

    mvc.perform(get("/addons/calendar?" + RANGE).with(user("user")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries.length()").value(2))
        .andExpect(jsonPath("$.entries[0].id").value("early"))
        .andExpect(jsonPath("$.entries[0].addon").value("demo"))
        .andExpect(jsonPath("$.entries[0].kind").value("appointment"))
        .andExpect(jsonPath("$.entries[0].title.de").value("Termin early"))
        .andExpect(jsonPath("$.entries[0].start").value("2026-11-03T09:00:00Z"))
        .andExpect(jsonPath("$.entries[0].link.slot").value("asker.navigation"))
        .andExpect(jsonPath("$.entries[1].id").value("late"))
        .andExpect(jsonPath("$.errors").isEmpty());

    assertThat(DEMO.lastCalendarRequest.get("uri"))
        .isEqualTo("/calendar?from=2026-11-01T00:00:00Z&to=2026-12-01T00:00:00Z");
    RSAKey key = (RSAKey) JWKSet.parse(issuer.publicJwks()).getKeys().getFirst();
    Jwt token =
        NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
            .build()
            .decode(DEMO.lastCalendarRequest.get("authorization").substring("Bearer ".length()));
    assertThat(token.getAudience()).containsExactly("demo");
    assertThat(token.getSubject()).isEqualTo("user-1");
    assertThat(token.hasClaim("sessionId")).isFalse();
  }

  @Test
  void listsFailingAndSlowAddonsAsErrorsAndSkipsDisabledOnes() throws Exception {
    enablement.setForTenant("broken", 1L, true, "test");
    enablement.setForTenant("slow", 1L, true, "test");
    DEMO.calendarBody = "{\"entries\":[" + entry("ok", "client-page", "reminder", "2026-11-03T09:00:00Z", "asker.navigation") + "]}";

    mvc.perform(get("/addons/calendar?" + RANGE).with(user("user")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries.length()").value(1))
        .andExpect(jsonPath("$.entries[0].id").value("ok"))
        .andExpect(jsonPath("$.errors.length()").value(2))
        .andExpect(jsonPath("$.errors[0]").value("broken"))
        .andExpect(jsonPath("$.errors[1]").value("slow"));

    assertThat(BROKEN.lastCalendarRequest).containsKey("uri");
    assertThat(DISABLED.lastCalendarRequest).isEmpty();
  }

  @Test
  void capsTheEntriesOfOneAddon() throws Exception {
    DEMO.calendarBody =
        "{\"entries\":["
            + IntStream.range(0, 250)
                .mapToObj(i -> entry("e" + i, "client-page", "reminder", "2026-11-03T09:00:00Z", "asker.navigation"))
                .collect(Collectors.joining(","))
            + "]}";
    mvc.perform(get("/addons/calendar?" + RANGE).with(user("user"))).andExpect(jsonPath("$.entries.length()").value(200));
  }

  @Test
  void validatesTheRange() throws Exception {
    mvc.perform(get("/addons/calendar?from=2026-11-02T00:00:00Z&to=2026-11-01T00:00:00Z").with(user("user")))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/addons/calendar?from=2026-01-01T00:00:00Z&to=2026-06-01T00:00:00Z").with(user("user")))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/addons/calendar?from=2026-11-01T00:00:00Z").with(user("user"))).andExpect(status().isBadRequest());
    mvc.perform(get("/addons/calendar?" + RANGE)).andExpect(status().isUnauthorized());
    assertThat(DEMO.lastCalendarRequest).isEmpty();
  }

  private static FakeAddon start(String name) {
    try {
      return new FakeAddon(name);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
