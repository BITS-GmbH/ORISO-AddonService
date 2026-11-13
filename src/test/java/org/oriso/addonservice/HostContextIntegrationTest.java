package org.oriso.addonservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.oriso.addonservice.discovery.AddonDiscovery;
import org.oriso.addonservice.security.AgencyMembership;
import org.oriso.addonservice.security.Caller;
import org.oriso.addonservice.security.SessionAccess;
import org.oriso.addonservice.token.AddonTokenIssuer;
import org.oriso.addonservice.token.TechnicalClientToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Host API: core gates, context and notifications to the counsellors of a client. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HostContextIntegrationTest {

  private static final FakeAddon ADDON = start(() -> new FakeAddon());
  private static final FakeCore CORE = start(FakeCore::new);

  @Autowired MockMvc mvc;
  @Autowired AddonDiscovery discovery;
  @Autowired AddonTokenIssuer issuer;
  @Autowired TechnicalClientToken technicalToken;
  @Autowired JsonMapper json;
  @MockitoBean AgencyMembership membership;
  @MockitoBean SessionAccess sessions;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("addons.discovery.static-addons", () -> ADDON.baseUrl().toString());
    registry.add("addons.user-service.base-url", () -> CORE.baseUrl().toString());
    registry.add("addons.user-service.context-cache-ttl", () -> "0s");
    registry.add("addons.tenant-service.base-url", () -> CORE.baseUrl().toString());
    registry.add("addons.technical-client.token-url", () -> CORE.baseUrl() + "/token");
    registry.add("addons.technical-client.client-secret", () -> "secret");
  }

  @AfterAll
  static void stop() {
    ADDON.close();
    CORE.close();
  }

  private interface Starter<T> {
    T start() throws Exception;
  }

  private static <T> T start(Starter<T> starter) {
    try {
      return starter.start();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static final String ASKER_CONTEXT =
      """
      { "userId": "asker-7", "type": "asker", "agencyIds": [10, 20],
        "sessions": [
          { "id": 42, "agencyId": 10, "consultantId": "consultant-1", "status": "IN_PROGRESS" },
          { "id": 43, "agencyId": 10, "consultantId": "consultant-2", "status": "IN_PROGRESS" },
          { "id": 51, "agencyId": 20, "consultantId": "consultant-3", "status": "IN_PROGRESS" },
          { "id": 52, "agencyId": 10, "consultantId": null, "status": "NEW" } ],
        "consultants": [
          { "id": "consultant-1", "username": "jane", "displayName": "Jane D" },
          { "id": "consultant-2", "username": "joe", "displayName": "Joe D" },
          { "id": "consultant-3", "username": "kim", "displayName": "Kim D" } ] }
      """;

  private static final Caller CONSULTANT =
      new Caller("consultant-1", "jane", Set.of("consultant"), 1L, "keycloak-token");
  private static final Caller ASKER = new Caller("asker-7", "client", Set.of("user"), 1L, "keycloak-token");

  @BeforeEach
  void setUp() {
    discovery.refresh();
    technicalToken.invalidate();
    CORE.notifications.clear();
    CORE.notificationStatus = 201;
    CORE.contextStatus = 200;
    CORE.contextBody = ASKER_CONTEXT;
  }

  private String token(Caller caller, Long agencyId, Long sessionId, String askerId) {
    return "Bearer " + issuer.issue("demo", caller, agencyId, sessionId, askerId);
  }

  @Test
  void answersTheDpaGateOfTheTokensTraegerAndCachesIt() throws Exception {
    int before = CORE.gateRequests.get();
    for (int i = 0; i < 2; i++) {
      mvc.perform(get("/addons/host/gates").header("Authorization", token(ASKER, null, null, null)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.dpa.status").value("OUTDATED"))
          .andExpect(jsonPath("$.dpa.newCounsellingAllowed").value(false))
          .andExpect(jsonPath("$.dpa.signed").value(false))
          .andExpect(jsonPath("$.dpa.dpaPublished").doesNotExist());
    }
    assertThat(CORE.gateRequests.get() - before).isEqualTo(1);
    assertThat(CORE.lastGateRequest.get("uri")).isEqualTo("/tenantadmin/1/dpa/gate");
    assertThat(CORE.lastGateRequest.get("authorization")).isEqualTo("Bearer service-token");
  }

  @Test
  void answersTheContextOfTheClientWithinTheTokensAgency() throws Exception {
    mvc.perform(get("/addons/host/context").header("Authorization", token(CONSULTANT, 10L, 42L, "asker-7")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value("asker-7"))
        .andExpect(jsonPath("$.agencyIds.length()").value(1))
        .andExpect(jsonPath("$.sessions.length()").value(3))
        .andExpect(jsonPath("$.consultants.length()").value(2));
    assertThat(CORE.lastContextRequest.get("uri")).isEqualTo("/users/addon-context?userId=asker-7&tenantId=1");
    assertThat(CORE.lastContextRequest.get("authorization")).isEqualTo("Bearer service-token");

    mvc.perform(get("/addons/host/context?subject=self").header("Authorization", token(ASKER, null, null, null)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessions.length()").value(4));
    assertThat(CORE.lastContextRequest.get("uri")).isEqualTo("/users/addon-context?userId=asker-7&tenantId=1");

    mvc.perform(get("/addons/host/context?subject=asker").header("Authorization", token(ASKER, null, null, null)))
        .andExpect(status().isForbidden());
    mvc.perform(get("/addons/host/context?subject=boss").header("Authorization", token(ASKER, null, null, null)))
        .andExpect(status().isBadRequest());

    CORE.contextStatus = 404;
    mvc.perform(get("/addons/host/context").header("Authorization", token(ASKER, null, null, null)))
        .andExpect(status().isNotFound());
    CORE.contextStatus = 500;
    mvc.perform(get("/addons/host/context").header("Authorization", token(ASKER, null, null, null)))
        .andExpect(status().isBadGateway());
  }

  private static String counsellorNotification(String slot) {
    return """
        { "recipient": "counsellors", "appId": "case-panel", "important": false,
          "title": { "de": "Antworten geteilt" },
          "text": { "de": "Ihr Klient hat seine Antworten geteilt." },
          "target": { "slot": "%s" } }
        """
        .formatted(slot);
  }

  @Test
  void notifiesTheCounsellorsOfTheClientInTheirOwnSession() throws Exception {
    mvc.perform(
            post("/addons/host/notifications")
                .header("Authorization", token(ASKER, 10L, null, null))
                .contentType(MediaType.APPLICATION_JSON)
                .content(counsellorNotification("consultant.session")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.ids.length()").value(2));

    assertThat(CORE.notifications).hasSize(2);
    var first = json.readTree(CORE.notifications.get(0));
    var second = json.readTree(CORE.notifications.get(1));
    assertThat(first.get("userId").asString()).isEqualTo("consultant-1");
    assertThat(first.get("sessionId").asLong()).isEqualTo(42);
    assertThat(second.get("userId").asString()).isEqualTo("consultant-2");
    assertThat(second.get("sessionId").asLong()).isEqualTo(43);
  }

  @Test
  void aCounsellorInASessionNotifiesOnlyTheOtherCounsellorsOfThatSession() throws Exception {
    mvc.perform(
            post("/addons/host/notifications")
                .header("Authorization", token(CONSULTANT, 10L, 42L, "asker-7"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(counsellorNotification("consultant.session")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.ids.length()").value(0));
    assertThat(CORE.notifications).isEmpty();
  }

  @Test
  void refusesCounsellorNotificationsWithoutAClientOrToASlotCounsellorsCannotOpen() throws Exception {
    mvc.perform(
            post("/addons/host/notifications")
                .header("Authorization", token(CONSULTANT, 10L, null, null))
                .contentType(MediaType.APPLICATION_JSON)
                .content(counsellorNotification("consultant.session")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/addons/host/notifications")
                .header("Authorization", token(ASKER, null, null, null))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "recipient": "counsellors", "appId": "client-page",
                      "title": { "de": "x" }, "text": { "de": "y" }, "target": { "slot": "asker.navigation" } }
                    """))
        .andExpect(status().isBadRequest());
    assertThat(CORE.notifications).isEmpty();
  }
}
