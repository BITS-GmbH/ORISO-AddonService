package org.oriso.addonservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
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
class GatewayIntegrationTest {

  private static final FakeAddon ADDON = start();

  @Autowired MockMvc mvc;
  @Autowired AddonDiscovery discovery;
  @Autowired EnablementService enablement;
  @Autowired AddonEnablementRepository repository;
  @Autowired AddonTokenIssuer issuer;
  @MockitoBean AgencyMembership membership;

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
    repository.deleteAll();
    discovery.refresh();
  }

  private static JwtRequestPostProcessor user(long tenantId, String... roles) {
    return jwt().jwt(
            j ->
                j.subject("user-1")
                    .claim("username", "jane")
                    .claim("tenantId", tenantId)
                    .claim("realm_access", Map.of("roles", List.of(roles))));
  }

  @Test
  void servesUiWithoutLogin() throws Exception {
    mvc.perform(get("/addons/demo/ui/entry.js"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "text/javascript"));
  }

  @Test
  void apiNeedsLoginAndEnablement() throws Exception {
    mvc.perform(get("/addons/demo/api/echo")).andExpect(status().isUnauthorized());
    mvc.perform(get("/addons/demo/api/echo").with(user(1, "consultant"))).andExpect(status().isForbidden());
    mvc.perform(get("/addons/unknown/api/echo").with(user(1, "consultant"))).andExpect(status().isNotFound());
  }

  @Test
  void forwardsWithASignedTokenAndNothingElseFromTheUser() throws Exception {
    enablement.setForTenant("demo", 1L, true, "test");
    when(membership.belongsTo(any(), eq(10L))).thenReturn(true);

    mvc.perform(
            post("/addons/demo/api/entries?x=1")
                .with(user(1, "consultant"))
                .header("X-Oriso-Agency-Id", "10")
                .header("X-Forwarded-For", "1.2.3.4")
                .cookie(new Cookie("keycloak", "secret"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"a\":1}"))
        .andExpect(status().isCreated())
        .andExpect(header().doesNotExist("Set-Cookie"))
        .andExpect(header().doesNotExist("X-Internal"));

    assertThat(ADDON.lastRequest)
        .containsEntry("method", "POST")
        .containsEntry("uri", "/api/entries?x=1")
        .containsEntry("body", "{\"a\":1}")
        .doesNotContainKeys("header:cookie", "header:x-forwarded-for");

    String token = ADDON.lastRequest.get("header:authorization").substring("Bearer ".length());
    RSAKey key = (RSAKey) JWKSet.parse(issuer.publicJwks()).getKeys().getFirst();
    Jwt decoded = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build().decode(token);
    assertThat(decoded.getAudience()).containsExactly("demo");
    assertThat(decoded.getSubject()).isEqualTo("user-1");
    assertThat(decoded.getClaimAsStringList("roles")).containsExactly("consultant");
    assertThat(((Number) decoded.getClaim("tenantId")).longValue()).isEqualTo(1L);
    assertThat(((Number) decoded.getClaim("agencyId")).longValue()).isEqualTo(10L);
  }

  @Test
  void rejectsAgenciesTheCallerDoesNotBelongTo() throws Exception {
    enablement.setForTenant("demo", 1L, true, "test");
    when(membership.belongsTo(any(), eq(99L))).thenReturn(false);
    mvc.perform(get("/addons/demo/api/echo").with(user(1, "consultant")).header("X-Oriso-Agency-Id", "99"))
        .andExpect(status().isForbidden());
  }

  @Test
  void respectsAgencyOverrides() throws Exception {
    enablement.setForTenant("demo", 1L, true, "test");
    enablement.setForAgency("demo", 1L, 10L, false, "test");
    when(membership.belongsTo(any(), eq(10L))).thenReturn(true);
    mvc.perform(get("/addons/demo/api/echo").with(user(1, "consultant")).header("X-Oriso-Agency-Id", "10"))
        .andExpect(status().isForbidden());
  }

  @Test
  void rejectsPathTraversal() throws Exception {
    mvc.perform(get("/addons/demo/ui/%2e%2e/api/echo")).andExpect(status().isBadRequest());
  }

  @Test
  void registryListsOnlyEnabledAddonsAndSlotsForTheCallersRole() throws Exception {
    mvc.perform(get("/addons/registry").with(user(1, "consultant")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.addons").isEmpty());

    enablement.setForTenant("demo", 1L, true, "test");

    mvc.perform(get("/addons/registry").with(user(1, "consultant")))
        .andExpect(jsonPath("$.contract").value("oriso-addon/v1"))
        .andExpect(jsonPath("$.addons[0].entry").value("/service/addons/demo/ui/entry.js"))
        .andExpect(jsonPath("$.addons[0].apiBase").value("/service/addons/demo/api"))
        .andExpect(jsonPath("$.addons[0].apps.length()").value(1))
        .andExpect(jsonPath("$.addons[0].apps[0].id").value("case-panel"))
        .andExpect(jsonPath("$.addons[0].apps[0].slots.length()").value(2));

    mvc.perform(get("/addons/registry").with(user(1, "user")))
        .andExpect(jsonPath("$.addons[0].apps.length()").value(1))
        .andExpect(jsonPath("$.addons[0].apps[0].id").value("client-page"));

    mvc.perform(get("/addons/registry").with(user(2, "user"))).andExpect(jsonPath("$.addons").isEmpty());
  }

  @Test
  void onlySuperAdminsSwitchATenantAndTenantAdminsTheirAgencies() throws Exception {
    String on = "{\"enabled\":true}";
    String off = "{\"enabled\":false}";

    mvc.perform(put("/addons/admin/addons/demo/tenants/1").with(user(1, "tenant-admin"))
            .contentType(MediaType.APPLICATION_JSON).content(on))
        .andExpect(status().isForbidden());

    mvc.perform(put("/addons/admin/addons/demo/tenants/1").with(user(0, "tenant-admin", "agency-admin"))
            .contentType(MediaType.APPLICATION_JSON).content(on))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true));

    mvc.perform(put("/addons/admin/addons/demo/tenants/1/agencies/10").with(user(1, "single-tenant-admin"))
            .contentType(MediaType.APPLICATION_JSON).content(off))
        .andExpect(status().isOk());

    mvc.perform(put("/addons/admin/addons/demo/tenants/2/agencies/20").with(user(1, "single-tenant-admin"))
            .contentType(MediaType.APPLICATION_JSON).content(off))
        .andExpect(status().isForbidden());

    mvc.perform(get("/addons/admin/addons").with(user(1, "tenant-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].manifest.name").value("demo"))
        .andExpect(jsonPath("$[0].enabled").value(true))
        .andExpect(jsonPath("$[0].agencyOverrides[0].agencyId").value(10));

    mvc.perform(get("/addons/admin/addons").with(user(1, "consultant"))).andExpect(status().isForbidden());
  }

  @Test
  void listsTheTraegerOfAnAddonForSuperAdminsOnly() throws Exception {
    enablement.setForTenant("demo", 2, true, "admin-1");
    enablement.setForAgency("demo", 2, 20, false, "admin-2");
    enablement.setForTenant("demo", 1, false, "admin-1");
    enablement.setForAgency("demo", 3, 30, true, "admin-3");
    enablement.setForAgency("demo", 3, 31, true, "admin-3");

    mvc.perform(get("/addons/admin/addons/demo/tenants").with(user(0, "tenant-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].tenantId").value(1))
        .andExpect(jsonPath("$[0].enabled").value(false))
        .andExpect(jsonPath("$[1].tenantId").value(2))
        .andExpect(jsonPath("$[1].enabled").value(true))
        .andExpect(jsonPath("$[1].updatedBy").value("admin-1"))
        .andExpect(jsonPath("$[1].agenciesDisabled").value(1))
        .andExpect(jsonPath("$[1].agenciesEnabled").value(0))
        .andExpect(jsonPath("$[2].tenantId").value(3))
        .andExpect(jsonPath("$[2].enabled").value(false))
        .andExpect(jsonPath("$[2].updatedAt").doesNotExist())
        .andExpect(jsonPath("$[2].agenciesEnabled").value(2));

    mvc.perform(get("/addons/admin/addons/demo/tenants").with(user(1, "tenant-admin")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/addons/admin/addons/unknown/tenants").with(user(0, "tenant-admin")))
        .andExpect(status().isNotFound());
  }

  @Test
  void publishesItsSigningKey() throws Exception {
    mvc.perform(get("/addons/.well-known/jwks.json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
        .andExpect(jsonPath("$.keys[0].d").doesNotExist());
  }

  private static FakeAddon start() {
    try {
      return new FakeAddon();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
