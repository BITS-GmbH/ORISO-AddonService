package org.oriso.addonservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HostApiIntegrationTest {

  private static final FakeAddon ADDON = start(() -> new FakeAddon());
  private static final FakeCore CORE = start(FakeCore::new);

  @Autowired MockMvc mvc;
  @Autowired AddonDiscovery discovery;
  @Autowired AddonTokenIssuer issuer;
  @Autowired TechnicalClientToken technicalToken;
  @Autowired JdbcTemplate jdbc;
  @Autowired JsonMapper json;
  @MockitoBean AgencyMembership membership;
  @MockitoBean SessionAccess sessions;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("addons.discovery.static-addons", () -> ADDON.baseUrl().toString());
    registry.add("addons.user-service.base-url", () -> CORE.baseUrl().toString());
    registry.add("addons.technical-client.token-url", () -> CORE.baseUrl() + "/token");
    registry.add("addons.technical-client.client-secret", () -> "secret");
  }

  @AfterAll
  static void stop() {
    ADDON.close();
    CORE.close();
  }

  @BeforeEach
  void setUp() {
    jdbc.update("delete from addon_audit_entry");
    discovery.refresh();
    technicalToken.invalidate();
    CORE.tokenRequests.set(0);
    CORE.lastNotification.clear();
    CORE.notificationStatus = 201;
  }

  private static final Caller CONSULTANT =
      new Caller("consultant-1", "jane", Set.of("consultant"), 1L, "keycloak-token");
  private static final Caller ASKER = new Caller("asker-7", "client", Set.of("user"), 1L, "keycloak-token");

  private String hostToken(Caller caller, Long agencyId, Long sessionId, String askerId) {
    return "Bearer " + issuer.issue("demo", caller, agencyId, sessionId, askerId);
  }

  private static MockHttpServletRequestBuilder hostPost(String path, String token, String body) {
    return post(path).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static String notification(String recipient, String appId, String slot) {
    return """
        { "recipient": "%s", "appId": "%s", "important": true,
          "title": { "de": "Neues Tool", "en": "New tool" },
          "text": { "de": "Ihre Beraterin hat ein Tool freigegeben." },
          "target": { "slot": "%s" } }
        """
        .formatted(recipient, appId, slot);
  }

  private JsonNode lastNotification() {
    return json.readTree(CORE.lastNotification.get("body"));
  }

  @Test
  void notifiesTheClientOfTheSessionThroughUserService() throws Exception {
    String token = hostToken(CONSULTANT, 10L, 42L, "asker-7");
    mvc.perform(hostPost("/addons/host/notifications", token, notification("asker", "client-page", "asker.navigation")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value("n-1"));

    assertThat(CORE.lastNotification.get("authorization")).isEqualTo("Bearer service-token");
    assertThat(CORE.lastTokenRequest.get("body"))
        .contains("grant_type=client_credentials", "client_id=backend-technical", "client_secret=secret");
    JsonNode sent = lastNotification();
    assertThat(sent.get("userId").asString()).isEqualTo("asker-7");
    assertThat(sent.get("tenantId").asLong()).isEqualTo(1L);
    assertThat(sent.get("addon").asString()).isEqualTo("demo");
    assertThat(sent.get("appId").asString()).isEqualTo("client-page");
    assertThat(sent.get("slot").asString()).isEqualTo("asker.navigation");
    assertThat(sent.get("sessionId").isNull()).isTrue();
    assertThat(sent.get("important").asBoolean()).isTrue();
    assertThat(sent.get("title").get("en").asString()).isEqualTo("New tool");
    assertThat(sent.get("text").get("de").asString()).isEqualTo("Ihre Beraterin hat ein Tool freigegeben.");
  }

  @Test
  void addsTheSessionOfTheTokenForSessionSlotsAndReusesTheServiceToken() throws Exception {
    String token = hostToken(CONSULTANT, null, 42L, "asker-7");
    for (int i = 0; i < 2; i++) {
      mvc.perform(hostPost("/addons/host/notifications", token, notification("self", "case-panel", "consultant.session")))
          .andExpect(status().isAccepted());
    }
    JsonNode sent = lastNotification();
    assertThat(sent.get("userId").asString()).isEqualTo("consultant-1");
    assertThat(sent.get("sessionId").asLong()).isEqualTo(42L);
    assertThat(CORE.tokenRequests.get()).isEqualTo(1);
  }

  @Test
  void refusesTheClientAsRecipientWithoutASessionClientInTheToken() throws Exception {
    String token = hostToken(CONSULTANT, null, null, null);
    mvc.perform(hostPost("/addons/host/notifications", token, notification("asker", "client-page", "asker.navigation")))
        .andExpect(status().isForbidden());
    assertThat(CORE.lastNotification).isEmpty();
  }

  @Test
  void validatesNotifications() throws Exception {
    String token = hostToken(ASKER, null, null, null);
    mvc.perform(hostPost("/addons/host/notifications", token, notification("self", "unknown", "asker.navigation")))
        .andExpect(status().isNotFound());
    mvc.perform(hostPost("/addons/host/notifications", token, notification("self", "client-page", "consultant.session")))
        .andExpect(status().isBadRequest());
    mvc.perform(hostPost("/addons/host/notifications", token, notification("someone", "client-page", "asker.navigation")))
        .andExpect(status().isBadRequest());
    mvc.perform(hostPost("/addons/host/notifications", token, notification("self", "client-page", "asker.navigation").replace("\"de\": \"Neues Tool\", ", "")))
        .andExpect(status().isBadRequest());
    mvc.perform(hostPost("/addons/host/notifications", token, notification("self", "client-page", "asker.navigation").replace("Neues Tool", "x".repeat(121))))
        .andExpect(status().isBadRequest());
    String counsellor = hostToken(CONSULTANT, null, null, null);
    mvc.perform(hostPost("/addons/host/notifications", counsellor, notification("self", "case-panel", "consultant.session")))
        .andExpect(status().isBadRequest());
    assertThat(CORE.lastNotification).isEmpty();
  }

  @Test
  void answersBadGatewayWhenUserServiceFails() throws Exception {
    CORE.notificationStatus = 500;
    String token = hostToken(ASKER, null, null, null);
    mvc.perform(hostPost("/addons/host/notifications", token, notification("self", "client-page", "asker.navigation")))
        .andExpect(status().isBadGateway());
  }

  @Test
  void theHostApiNeedsAnAddonToken() throws Exception {
    mvc.perform(hostPost("/addons/host/notifications", "Bearer not-a-token", notification("self", "client-page", "asker.navigation")))
        .andExpect(status().isUnauthorized());
    mvc.perform(hostPost("/addons/host/audit", "Bearer not-a-token", "{}")).andExpect(status().isUnauthorized());
  }

  private static String auditEntry(String action, String subject) {
    return """
        { "action": "%s", "appId": "case-panel", "subject": %s,
          "summary": { "de": "Tool freigegeben", "en": "Released tool" } }
        """
        .formatted(action, subject == null ? "null" : "\"" + subject + "\"");
  }

  private void writeAudit(Caller caller, Long agencyId, String action) throws Exception {
    mvc.perform(hostPost("/addons/host/audit", hostToken(caller, agencyId, 42L, "asker-7"), auditEntry(action, "asker")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNumber());
  }

  private static JwtRequestPostProcessor admin(long tenantId, String... roles) {
    return jwt().jwt(
            j ->
                j.subject("admin-1")
                    .claim("tenantId", tenantId)
                    .claim("realm_access", Map.of("roles", List.of(roles))));
  }

  @Test
  void storesAuditEntriesWithTheContextOfTheToken() throws Exception {
    writeAudit(CONSULTANT, 10L, "tool.released");

    mvc.perform(get("/addons/admin/audit").with(admin(1, "tenant-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(50))
        .andExpect(jsonPath("$.items[0].addon").value("demo"))
        .andExpect(jsonPath("$.items[0].appId").value("case-panel"))
        .andExpect(jsonPath("$.items[0].action").value("tool.released"))
        .andExpect(jsonPath("$.items[0].summary.de").value("Tool freigegeben"))
        .andExpect(jsonPath("$.items[0].actor.id").value("consultant-1"))
        .andExpect(jsonPath("$.items[0].actor.username").value("jane"))
        .andExpect(jsonPath("$.items[0].actor.roles[0]").value("consultant"))
        .andExpect(jsonPath("$.items[0].tenantId").value(1))
        .andExpect(jsonPath("$.items[0].agencyId").value(10))
        .andExpect(jsonPath("$.items[0].sessionId").value(42))
        .andExpect(jsonPath("$.items[0].subjectId").value("asker-7"))
        .andExpect(jsonPath("$.items[0].createdAt").isString());
  }

  @Test
  void validatesAuditEntries() throws Exception {
    String token = hostToken(CONSULTANT, null, null, null);
    mvc.perform(hostPost("/addons/host/audit", token, auditEntry("Tool Released", null))).andExpect(status().isBadRequest());
    mvc.perform(hostPost("/addons/host/audit", token, auditEntry("tool.released", "someone"))).andExpect(status().isBadRequest());
    mvc.perform(hostPost("/addons/host/audit", token, auditEntry("tool.released", null).replace("Tool freigegeben", "x".repeat(301))))
        .andExpect(status().isBadRequest());
    mvc.perform(hostPost("/addons/host/audit", token, auditEntry("tool.released", null).replace("case-panel", "unknown")))
        .andExpect(status().isNotFound());
    mvc.perform(hostPost("/addons/host/audit", token, auditEntry("tool.released", "asker"))).andExpect(status().isForbidden());
    mvc.perform(hostPost("/addons/host/audit", token, auditEntry("tool.released", "self"))).andExpect(status().isCreated());
  }

  @Test
  void auditEntriesAreScopedToTheAdminsTragerAndAgencies() throws Exception {
    writeAudit(CONSULTANT, 10L, "first");
    writeAudit(CONSULTANT, 11L, "second");
    writeAudit(new Caller("consultant-2", "max", Set.of("consultant"), 2L, "t"), 20L, "other-trager");

    mvc.perform(get("/addons/admin/audit").with(admin(1, "single-tenant-admin")))
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.items[0].action").value("second"))
        .andExpect(jsonPath("$.items[1].action").value("first"));
    mvc.perform(get("/addons/admin/audit?tenantId=2").with(admin(1, "tenant-admin"))).andExpect(status().isForbidden());

    mvc.perform(get("/addons/admin/audit").with(admin(0, "tenant-admin"))).andExpect(jsonPath("$.total").value(3));
    mvc.perform(get("/addons/admin/audit?tenantId=2").with(admin(0, "tenant-admin")))
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].action").value("other-trager"));

    when(membership.agenciesOf(any())).thenReturn(Set.of(11L));
    mvc.perform(get("/addons/admin/audit").with(admin(1, "agency-admin")))
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].action").value("second"));
    when(membership.agenciesOf(any())).thenReturn(Set.of());
    mvc.perform(get("/addons/admin/audit").with(admin(1, "restricted-agency-admin"))).andExpect(jsonPath("$.total").value(0));

    mvc.perform(get("/addons/admin/audit").with(admin(1, "consultant"))).andExpect(status().isForbidden());
  }

  @Test
  void auditEntriesArePagedAndFiltered() throws Exception {
    writeAudit(CONSULTANT, 10L, "first");
    writeAudit(CONSULTANT, 10L, "second");
    writeAudit(CONSULTANT, 10L, "third");

    mvc.perform(get("/addons/admin/audit?page=1&size=2").with(admin(1, "tenant-admin")))
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].action").value("first"));
    mvc.perform(get("/addons/admin/audit?addon=other").with(admin(1, "tenant-admin"))).andExpect(jsonPath("$.total").value(0));
    mvc.perform(get("/addons/admin/audit?addon=demo&from=2000-01-01T00:00:00Z&to=2999-01-01T00:00:00Z").with(admin(1, "tenant-admin")))
        .andExpect(jsonPath("$.total").value(3));
    mvc.perform(get("/addons/admin/audit?from=2999-01-01T00:00:00Z").with(admin(1, "tenant-admin"))).andExpect(jsonPath("$.total").value(0));
    mvc.perform(get("/addons/admin/audit?size=500").with(admin(1, "tenant-admin"))).andExpect(status().isBadRequest());
    mvc.perform(get("/addons/admin/audit?from=yesterday").with(admin(1, "tenant-admin"))).andExpect(status().isBadRequest());
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
}
