package org.oriso.addonservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.oriso.addonservice.discovery.AddonDiscovery;
import org.oriso.addonservice.enablement.AddonEnablementRepository;
import org.oriso.addonservice.enablement.EnablementService;
import org.oriso.addonservice.security.AgencyMembership;
import org.oriso.addonservice.security.Caller;
import org.oriso.addonservice.security.SessionAccess;
import org.oriso.addonservice.token.AddonTokenIssuer;
import org.oriso.addonservice.token.TechnicalClientToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistryVisibilityIntegrationTest {

  private static final ToolsAddon ADDON = start(ToolsAddon::new);
  private static final FakeCore CORE = start(FakeCore::new);
  private static final String VISIBLE = "{\"apps\":{\"goals\":{\"visible\":true}}}";

  @Autowired MockMvc mvc;
  @Autowired AddonDiscovery discovery;
  @Autowired EnablementService enablement;
  @Autowired AddonEnablementRepository repository;
  @Autowired AddonTokenIssuer issuer;
  @Autowired TechnicalClientToken technicalToken;
  @MockitoBean AgencyMembership membership;
  @MockitoBean SessionAccess sessions;

  private String userId;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("addons.discovery.static-addons", () -> ADDON.baseUrl().toString());
    registry.add("addons.visibility.timeout", () -> "300ms");
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
    repository.deleteAll();
    discovery.refresh();
    technicalToken.invalidate();
    enablement.setForTenant("tools", 1L, true, "test");
    ADDON.calls.set(0);
    ADDON.status = 200;
    ADDON.body = VISIBLE;
    ADDON.delayMs = 0;
    userId = UUID.randomUUID().toString();
  }

  private JwtRequestPostProcessor user(String... roles) {
    return jwt().jwt(
            j -> j.subject(userId).claim("tenantId", 1L).claim("realm_access", Map.of("roles", List.of(roles))));
  }

  private ResultActions registry(JwtRequestPostProcessor user, String header, String value) throws Exception {
    var request = get("/addons/registry").with(user);
    return mvc.perform(header == null ? request : request.header(header, value)).andExpect(status().isOk());
  }

  @Test
  void anAddOnDisabledForTheAgencyOfTheHeaderIsAbsent() throws Exception {
    enablement.setForAgency("tools", 1L, 10L, false, "test");
    when(membership.belongsTo(any(), eq(10L))).thenReturn(true);
    when(membership.belongsTo(any(), eq(11L))).thenReturn(true);

    registry(user("user"), "X-Oriso-Agency-Id", "10").andExpect(jsonPath("$.addons").isEmpty());
    registry(user("user"), "X-Oriso-Agency-Id", "11").andExpect(jsonPath("$.addons[0].name").value("tools"));
  }

  @Test
  void withoutHeadersTheCallersOwnAgenciesDecide() throws Exception {
    enablement.setForAgency("tools", 1L, 10L, false, "test");

    when(membership.requireAgenciesOf(any())).thenReturn(Set.of(10L));
    registry(user("consultant"), null, null).andExpect(jsonPath("$.addons").isEmpty());

    when(membership.requireAgenciesOf(any())).thenReturn(Set.of(10L, 11L));
    registry(user("consultant"), null, null).andExpect(jsonPath("$.addons[0].name").value("tools"));

    when(membership.requireAgenciesOf(any())).thenReturn(Set.of());
    registry(user("consultant"), null, null).andExpect(jsonPath("$.addons[0].name").value("tools"));
  }

  @Test
  void aSessionHeaderUsesTheSessionsAgencyAndPassesTheSessionToTheAddOn() throws Exception {
    enablement.setForAgency("tools", 1L, 10L, false, "test");
    when(sessions.find(any(), eq(42L))).thenReturn(Optional.of(new SessionAccess.SessionInfo("asker-7", 10L)));
    when(sessions.find(any(), eq(43L))).thenReturn(Optional.of(new SessionAccess.SessionInfo("asker-8", 20L)));

    registry(user("consultant"), "X-Oriso-Session-Id", "42").andExpect(jsonPath("$.addons").isEmpty());
    registry(user("consultant"), "X-Oriso-Session-Id", "43")
        .andExpect(jsonPath("$.addons[0].apps.length()").value(2))
        .andExpect(jsonPath("$.addons[0].apps[1].id").value("goals"))
        .andExpect(jsonPath("$.addons[0].apps[1].slots[0]").value("consultant.session"));

    Jwt token = decode(ADDON.lastAuthorization);
    assertThat(token.getSubject()).isEqualTo(userId);
    assertThat(((Number) token.getClaim("agencyId")).longValue()).isEqualTo(20L);
    assertThat(((Number) token.getClaim("sessionId")).longValue()).isEqualTo(43L);
    assertThat(token.getClaimAsString("askerId")).isEqualTo("asker-8");
    verify(membership, never()).requireAgenciesOf(any());
  }

  @Test
  void aDynamicAppIsHiddenWhenTheAddOnSaysSo() throws Exception {
    ADDON.body = "{\"apps\":{\"goals\":{\"visible\":false}}}";
    assertOnlyStaticApp();
  }

  @Test
  void aDynamicAppIsHiddenWhenTheAddOnIsTooSlow() throws Exception {
    ADDON.delayMs = 1000;
    assertOnlyStaticApp();
  }

  @Test
  void aDynamicAppIsHiddenWhenTheAddOnFails() throws Exception {
    ADDON.status = 500;
    assertOnlyStaticApp();
  }

  @Test
  void aDynamicAppMissingFromTheAnswerIsHidden() throws Exception {
    ADDON.body = "{\"apps\":{}}";
    assertOnlyStaticApp();
  }

  private void assertOnlyStaticApp() throws Exception {
    registry(user("user"), null, null)
        .andExpect(jsonPath("$.addons[0].apps.length()").value(1))
        .andExpect(jsonPath("$.addons[0].apps[0].id").value("notes"));
  }

  @Test
  void passesTheBadgeOfDynamicApps() throws Exception {
    ADDON.body = "{\"apps\":{\"goals\":{\"visible\":true,\"badge\":3}}}";
    registry(user("user"), null, null)
        .andExpect(jsonPath("$.addons[0].apps[0].id").value("notes"))
        .andExpect(jsonPath("$.addons[0].apps[0].badge").doesNotExist())
        .andExpect(jsonPath("$.addons[0].apps[1].id").value("goals"))
        .andExpect(jsonPath("$.addons[0].apps[1].badge").value(3));
  }

  @Test
  void aZeroOrInvalidBadgeIsNone() throws Exception {
    ADDON.body = "{\"apps\":{\"goals\":{\"visible\":true,\"badge\":0}}}";
    registry(user("user"), null, null).andExpect(jsonPath("$.addons[0].apps[1].badge").doesNotExist());
  }

  @Test
  void doesNotAskForRolesWithoutADynamicApp() throws Exception {
    registry(user("agency-admin"), null, null).andExpect(jsonPath("$.addons").isEmpty());
    assertThat(ADDON.calls).hasValue(0);
  }

  @Test
  void cachesTheAnswerUntilTheAddOnNotifiesTheUser() throws Exception {
    ADDON.body = "{\"apps\":{\"goals\":{\"visible\":false}}}";
    assertOnlyStaticApp();
    ADDON.body = VISIBLE;
    assertOnlyStaticApp();
    assertThat(ADDON.calls).hasValue(1);

    var client = new Caller(userId, "client", Set.of("user"), 1L, "keycloak-token");
    mvc.perform(
            post("/addons/host/notifications")
                .header("Authorization", "Bearer " + issuer.issue("tools", client, null))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "recipient": "self", "appId": "goals",
                      "title": { "de": "Neues Tool" }, "text": { "de": "Freigegeben" },
                      "target": { "slot": "asker.navigation" } }
                    """))
        .andExpect(status().isAccepted());

    registry(user("user"), null, null).andExpect(jsonPath("$.addons[0].apps[1].id").value("goals"));
    assertThat(ADDON.calls).hasValue(2);
  }

  private Jwt decode(String authorization) throws Exception {
    RSAKey key = (RSAKey) JWKSet.parse(issuer.publicJwks()).getKeys().getFirst();
    return NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
        .build()
        .decode(authorization.substring("Bearer ".length()));
  }

  private interface Starter<T> {
    T start() throws IOException;
  }

  private static <T> T start(Starter<T> starter) {
    try {
      return starter.start();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** An add-on with a static app and a dynamic one whose visibility answer the test controls. */
  static final class ToolsAddon implements AutoCloseable {

    private static final String MANIFEST =
        """
        {
          "name": "tools", "version": "1.0.0", "contract": "oriso-addon/v1", "react": "^19",
          "ui": "/ui/entry.js",
          "apps": [
            { "id": "notes", "title": { "de": "Notizen" }, "slots": ["asker.navigation", "consultant.session"] },
            { "id": "goals", "title": { "de": "Ziele" }, "visibility": "dynamic",
              "slots": ["asker.navigation", "consultant.session"] }
          ]
        }
        """;

    final AtomicInteger calls = new AtomicInteger();
    volatile int status = 200;
    volatile String body = VISIBLE;
    volatile long delayMs;
    volatile String lastAuthorization;
    private final HttpServer server;

    ToolsAddon() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.createContext("/.well-known/oriso-addon.json", ex -> reply(ex, 200, MANIFEST));
      server.createContext("/visibility", this::visibility);
      server.start();
    }

    URI baseUrl() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void visibility(HttpExchange exchange) throws IOException {
      calls.incrementAndGet();
      lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
      if (delayMs > 0) {
        try {
          Thread.sleep(delayMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      reply(exchange, status, body);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (var out = exchange.getResponseBody()) {
        out.write(bytes);
      } catch (IOException ignored) {
        exchange.close();
      }
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
