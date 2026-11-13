package org.oriso.addonservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.oriso.addonservice.discovery.AddonDiscovery;
import org.oriso.addonservice.enablement.AddonEnablementRepository;
import org.oriso.addonservice.enablement.EnablementService;
import org.oriso.addonservice.security.AgencyMembership;
import org.oriso.addonservice.security.Caller;
import org.oriso.addonservice.security.SessionAccess;
import org.oriso.addonservice.settings.AddonSettingRepository;
import org.oriso.addonservice.token.AddonTokenIssuer;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionSettingsStatsIntegrationTest {

  private static final FakeAddon ADDON = start();

  @Autowired MockMvc mvc;
  @Autowired AddonDiscovery discovery;
  @Autowired EnablementService enablement;
  @Autowired AddonEnablementRepository enablements;
  @Autowired AddonSettingRepository settings;
  @Autowired AddonTokenIssuer issuer;
  @MockitoBean AgencyMembership membership;
  @MockitoBean SessionAccess sessions;

  @DynamicPropertySource
  static void addons(DynamicPropertyRegistry registry) {
    registry.add("addons.discovery.static-addons", () -> ADDON.baseUrl().toString());
  }

  @AfterAll
  static void stop() {
    ADDON.close();
  }

  @BeforeEach
  void setUp() {
    enablements.deleteAll();
    enablement.setGlobal("demo", true, "test");
    settings.deleteAll();
    discovery.refresh();
    enablement.setForTenant("demo", 1L, true, "test");
  }

  private static JwtRequestPostProcessor user(long tenantId, String... roles) {
    return jwt().jwt(
            j ->
                j.subject("user-1")
                    .claim("tenantId", tenantId)
                    .claim("realm_access", Map.of("roles", List.of(roles))));
  }

  private Jwt decode(String authorization) throws Exception {
    RSAKey key = (RSAKey) JWKSet.parse(issuer.publicJwks()).getKeys().getFirst();
    return NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
        .build()
        .decode(authorization.substring("Bearer ".length()));
  }

  @Test
  void passesAVerifiedSessionAndItsClientToTheAddon() throws Exception {
    when(sessions.find(any(), eq(42L))).thenReturn(Optional.of(new SessionAccess.SessionInfo("asker-7", 10L)));
    mvc.perform(get("/addons/demo/api/entries").with(user(1, "consultant")).header("X-Oriso-Session-Id", "42"))
        .andExpect(status().isCreated());
    Jwt token = decode(ADDON.lastRequest.get("header:authorization"));
    assertThat(((Number) token.getClaim("sessionId")).longValue()).isEqualTo(42L);
    assertThat(token.getClaimAsString("askerId")).isEqualTo("asker-7");
  }

  @Test
  void refusesSessionsTheCounsellorMayNotSee() throws Exception {
    when(sessions.find(any(), anyLong())).thenReturn(Optional.empty());
    mvc.perform(get("/addons/demo/api/entries").with(user(1, "consultant")).header("X-Oriso-Session-Id", "43"))
        .andExpect(status().isForbidden());
  }

  @Test
  void ignoresTheSessionHeaderOfClients() throws Exception {
    mvc.perform(get("/addons/demo/api/entries").with(user(1, "user")).header("X-Oriso-Session-Id", "42"))
        .andExpect(status().isCreated());
    verify(sessions, never()).find(any(), anyLong());
    assertThat(decode(ADDON.lastRequest.get("header:authorization")).hasClaim("askerId")).isFalse();
  }

  @Test
  void adminsReadAndValidateSettings() throws Exception {
    mvc.perform(get("/addons/admin/addons/demo/settings?tenantId=1").with(user(1, "tenant-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schema.properties.intro.title").value("Einleitung"))
        .andExpect(jsonPath("$.values").isEmpty());

    mvc.perform(
            put("/addons/admin/addons/demo/settings?tenantId=1")
                .with(user(1, "tenant-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"intro\":\"viel zu lange Einleitung\",\"limit\":12}"))
        .andExpect(status().isBadRequest());

    mvc.perform(
            put("/addons/admin/addons/demo/settings?tenantId=1")
                .with(user(1, "tenant-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"intro\":\"Willkommen\",\"limit\":3}"))
        .andExpect(status().isOk());

    mvc.perform(
            put("/addons/admin/addons/demo/settings?tenantId=1&agencyId=10")
                .with(user(1, "tenant-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"intro\":\"Moin\"}"))
        .andExpect(status().isOk());

    mvc.perform(get("/addons/admin/addons/demo/settings?tenantId=2").with(user(1, "tenant-admin")))
        .andExpect(status().isForbidden());
  }

  @Test
  void addonsReadTheirEffectiveSettingsWithTheirOwnToken() throws Exception {
    mvc.perform(
            put("/addons/admin/addons/demo/settings?tenantId=1")
                .with(user(1, "tenant-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"intro\":\"Willkommen\",\"limit\":3}"))
        .andExpect(status().isOk());
    mvc.perform(
            put("/addons/admin/addons/demo/settings?tenantId=1&agencyId=10")
                .with(user(1, "tenant-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"intro\":\"Moin\"}"))
        .andExpect(status().isOk());

    var caller = new Caller("user-1", "jane", Set.of("consultant"), 1L, "keycloak-token");
    mvc.perform(get("/addons/host/settings").header("Authorization", "Bearer " + issuer.issue("demo", caller, null)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.intro").value("Willkommen"))
        .andExpect(jsonPath("$.limit").value(3))
        .andExpect(jsonPath("$.showHints").value(true));
    mvc.perform(get("/addons/host/settings").header("Authorization", "Bearer " + issuer.issue("demo", caller, 10L)))
        .andExpect(jsonPath("$.intro").value("Moin"))
        .andExpect(jsonPath("$.limit").value(3));
  }

  @Test
  void theHostApiRejectsTokensItDidNotIssueForOneAddon() throws Exception {
    mvc.perform(get("/addons/host/settings").header("Authorization", "Bearer not-a-token"))
        .andExpect(status().isUnauthorized());
    // jwt() skips signature checks; a principal without an add-on audience is still refused.
    mvc.perform(get("/addons/host/settings").with(user(1, "consultant"))).andExpect(status().isForbidden());
  }

  @Test
  void statisticsComeFromTheAddonWithAServiceToken() throws Exception {
    mvc.perform(get("/addons/admin/addons/demo/stats?tenantId=1").with(user(1, "tenant-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stats[0].id").value("entries"))
        .andExpect(jsonPath("$.stats[0].label.de").value("Einträge"))
        .andExpect(jsonPath("$.stats[0].value").value(7))
        .andExpect(jsonPath("$.stats[1].value").doesNotExist());

    assertThat(ADDON.lastStatsRequest.get("uri")).isEqualTo("/stats?tenantId=1");
    Jwt token = decode(ADDON.lastStatsRequest.get("authorization"));
    assertThat(token.getAudience()).containsExactly("demo");
    assertThat(token.getClaimAsStringList("roles")).containsExactly(AddonTokenIssuer.SERVICE_ROLE);
    assertThat(((Number) token.getClaim("tenantId")).longValue()).isEqualTo(1L);
  }

  private static FakeAddon start() {
    try {
      return new FakeAddon();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
