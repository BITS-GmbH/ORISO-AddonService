package org.oriso.addonservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.oriso.addonservice.discovery.AddonDiscovery;
import org.oriso.addonservice.enablement.AddonEnablementRepository;
import org.oriso.addonservice.enablement.EnablementService;
import org.oriso.addonservice.enablement.TenantMode;
import org.oriso.addonservice.token.AddonTokenIssuer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The registration without an account: which add-on picks the agency, and its public API. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicRegistrationIntegrationTest {

  private static final FakeAddon DEMO = start("demo", FakeAddon.MANIFEST);
  private static final FakeAddon ALPHA = start("alpha", FakeAddon.AGENCY_FINDER_MANIFEST);
  private static final FakeAddon BETA = start("beta", FakeAddon.AGENCY_FINDER_MANIFEST);

  private static int nextAddress;

  @Autowired MockMvc mvc;
  @Autowired AddonDiscovery discovery;
  @Autowired EnablementService enablement;
  @Autowired AddonEnablementRepository repository;
  @Autowired AddonTokenIssuer issuer;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add(
        "addons.discovery.static-addons",
        () -> String.join(",", DEMO.baseUrl().toString(), ALPHA.baseUrl().toString(), BETA.baseUrl().toString()));
    registry.add("addons.public-gateway.cache-ttl", () -> "0s");
    registry.add("addons.public-gateway.requests-per-minute", () -> "3");
    registry.add("addons.public-gateway.max-body-size", () -> "32B");
  }

  @AfterAll
  static void stop() {
    DEMO.close();
    ALPHA.close();
    BETA.close();
  }

  private static FakeAddon start(String name, String manifest) {
    try {
      return new FakeAddon(name, manifest);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @BeforeEach
  void setUp() {
    repository.deleteAll();
    for (String name : new String[] {"demo", "alpha", "beta"}) {
      enablement.setGlobal(name, true, "test");
    }
    discovery.refresh();
  }

  /** A fresh client address per request group, so the rate limit only bites where a test wants it. */
  private static String newAddress() {
    return "10.0.0." + (++nextAddress);
  }

  private static MockHttpServletRequestBuilder publicApi(String addon, String address) {
    return get("/addons/" + addon + "/public/api/questions").header("X-Forwarded-For", address + ", 10.1.1.1");
  }

  @Test
  void registryOffersNothingUntilAnAddonIsOnForTheTraeger() throws Exception {
    mvc.perform(get("/addons/public/registration?tenantId=1")).andExpect(status().isNoContent());

    enablement.setMode("alpha", 1L, TenantMode.TENANT, "root");
    mvc.perform(get("/addons/public/registration?tenantId=1")).andExpect(status().isNoContent());

    enablement.setForTenant("alpha", 1L, true, "admin");
    mvc.perform(get("/addons/public/registration?tenantId=1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contract").value("oriso-addon/v1"))
        .andExpect(jsonPath("$.name").value("alpha"))
        .andExpect(jsonPath("$.appId").value("finder"))
        .andExpect(jsonPath("$.title.de").value("Beratungsstelle finden"))
        .andExpect(jsonPath("$.icon").value("/service/addons/alpha/ui/icons/finder.svg"))
        .andExpect(jsonPath("$.entry").value("/service/addons/alpha/ui/entry.js"))
        .andExpect(jsonPath("$.apiBase").value("/service/addons/alpha/public/api"));

    mvc.perform(get("/addons/public/registration?tenantId=2")).andExpect(status().isNoContent());
    mvc.perform(get("/addons/public/registration?tenantId=999999")).andExpect(status().isNoContent());
    mvc.perform(get("/addons/public/registration")).andExpect(status().isBadRequest());
  }

  @Test
  void registryPicksTheFirstAddonByNameAndIgnoresAgencyOverrides() throws Exception {
    enablement.setMode("beta", 1L, TenantMode.ON_HIDDEN, "root");
    mvc.perform(get("/addons/public/registration?tenantId=1")).andExpect(jsonPath("$.name").value("beta"));

    enablement.setMode("alpha", 1L, TenantMode.ON, "root");
    enablement.setForAgency("alpha", 1L, 10L, false, "admin");
    mvc.perform(get("/addons/public/registration?tenantId=1")).andExpect(jsonPath("$.name").value("alpha"));

    enablement.setGlobal("alpha", false, "root");
    mvc.perform(get("/addons/public/registration?tenantId=1")).andExpect(jsonPath("$.name").value("beta"));

    enablement.setMode("demo", 1L, TenantMode.ON, "root");
    enablement.setMode("beta", 1L, TenantMode.OFF, "root");
    mvc.perform(get("/addons/public/registration?tenantId=1")).andExpect(status().isNoContent());
  }

  @Test
  void publicApiNeedsTheTraegerHeaderAndAnAgencyFinderOnForIt() throws Exception {
    enablement.setMode("alpha", 1L, TenantMode.ON, "root");
    enablement.setMode("demo", 1L, TenantMode.ON, "root");

    mvc.perform(publicApi("alpha", newAddress())).andExpect(status().isBadRequest());
    mvc.perform(publicApi("alpha", newAddress()).header("X-Oriso-Tenant-Id", "x")).andExpect(status().isBadRequest());
    mvc.perform(publicApi("alpha", newAddress()).header("X-Oriso-Tenant-Id", "2")).andExpect(status().isForbidden());
    mvc.perform(publicApi("beta", newAddress()).header("X-Oriso-Tenant-Id", "1")).andExpect(status().isForbidden());
    mvc.perform(publicApi("demo", newAddress()).header("X-Oriso-Tenant-Id", "1")).andExpect(status().isForbidden());
    mvc.perform(publicApi("unknown", newAddress()).header("X-Oriso-Tenant-Id", "1")).andExpect(status().isNotFound());
    mvc.perform(get("/addons/alpha/api/questions").header("X-Oriso-Tenant-Id", "1")).andExpect(status().isUnauthorized());
  }

  @Test
  void forwardsWithAnAnonymousTokenAndNothingFromTheBrowser() throws Exception {
    enablement.setMode("alpha", 1L, TenantMode.ON, "root");

    mvc.perform(
            post("/addons/alpha/public/api/answers?step=2")
                .header("X-Forwarded-For", newAddress())
                .header("X-Oriso-Tenant-Id", "1")
                .header("Authorization", "Bearer not-a-keycloak-token")
                .cookie(new Cookie("keycloak", "secret"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"postcode\":\"10115\"}"))
        .andExpect(status().isCreated());

    assertThat(ALPHA.lastRequest)
        .containsEntry("method", "POST")
        .containsEntry("uri", "/public/api/answers?step=2")
        .containsEntry("body", "{\"postcode\":\"10115\"}")
        .doesNotContainKeys("header:cookie", "header:x-forwarded-for", "header:x-oriso-tenant-id");

    Jwt token = issuer.decoder().decode(ALPHA.lastRequest.get("header:authorization").substring("Bearer ".length()));
    assertThat(token.getAudience()).containsExactly("alpha");
    assertThat(token.getClaimAsStringList("roles")).containsExactly("anonymous");
    assertThat(((Number) token.getClaim("tenantId")).longValue()).isEqualTo(1L);
    assertThat(token.getSubject()).isNull();
    assertThat(token.getClaims()).doesNotContainKeys("sub", "username", "agencyId", "sessionId", "askerId");
    assertThat(token.getExpiresAt()).isBeforeOrEqualTo(token.getIssuedAt().plusSeconds(60));
  }

  @Test
  void limitsRequestsPerClientAddressAndAddon() throws Exception {
    enablement.setMode("alpha", 1L, TenantMode.ON, "root");
    enablement.setMode("beta", 1L, TenantMode.ON, "root");
    String address = newAddress();
    for (int i = 0; i < 3; i++) {
      mvc.perform(publicApi("alpha", address).header("X-Oriso-Tenant-Id", "1")).andExpect(status().isCreated());
    }
    mvc.perform(publicApi("alpha", address).header("X-Oriso-Tenant-Id", "1")).andExpect(status().isTooManyRequests());
    mvc.perform(publicApi("beta", address).header("X-Oriso-Tenant-Id", "1")).andExpect(status().isCreated());
    mvc.perform(publicApi("alpha", newAddress()).header("X-Oriso-Tenant-Id", "1")).andExpect(status().isCreated());
  }

  @Test
  void rejectsBodiesAboveTheLimit() throws Exception {
    enablement.setMode("alpha", 1L, TenantMode.ON, "root");
    mvc.perform(
            post("/addons/alpha/public/api/answers")
                .header("X-Forwarded-For", newAddress())
                .header("X-Oriso-Tenant-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + "x".repeat(40) + "\"}"))
        .andExpect(status().isContentTooLarge());
  }

  @Test
  void anonymousTokensCannotReadOrWriteAnythingPersonalOnTheHostApi() throws Exception {
    String token = "Bearer " + issuer.issueAnonymous("alpha", 1L);
    mvc.perform(get("/addons/host/context").header("Authorization", token)).andExpect(status().isForbidden());
    mvc.perform(
            post("/addons/host/audit")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"x\",\"summary\":{\"de\":\"x\"}}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/addons/host/notifications")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
  }
}
