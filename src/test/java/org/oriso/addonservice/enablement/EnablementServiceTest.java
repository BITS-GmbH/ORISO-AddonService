package org.oriso.addonservice.enablement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class EnablementServiceTest {

  @Autowired EnablementService enablement;
  @Autowired AddonEnablementRepository repository;
  @Autowired AddonGlobalSwitchRepository globalSwitches;

  @BeforeEach
  void clean() {
    repository.deleteAll();
    globalSwitches.deleteAll();
    enablement.setGlobal("demo", true, "admin");
  }

  @Test
  void isOffEverywhereUntilSwitchedOnGlobally() {
    enablement.setForTenant("other", 1L, true, "admin");
    enablement.setForAgency("other", 1L, 10L, true, "admin");
    assertThat(enablement.isGloballyEnabled("other")).isFalse();
    assertThat(enablement.isEnabled("other", 1L, null)).isFalse();
    assertThat(enablement.isEnabled("other", 1L, 10L)).isFalse();

    enablement.setGlobal("other", true, "admin");
    assertThat(enablement.isEnabled("other", 1L, null)).isTrue();
    assertThat(enablement.isEnabled("other", 1L, 10L)).isTrue();

    enablement.setGlobal("other", false, "root");
    assertThat(enablement.isEnabled("other", 1L, 10L)).isFalse();
    assertThat(enablement.globalSwitch("other")).hasValueSatisfying(row -> {
      assertThat(row.isEnabled()).isFalse();
      assertThat(row.getUpdatedBy()).isEqualTo("root");
    });
  }

  @Test
  void isOffUntilEnabledForTheTenant() {
    assertThat(enablement.isEnabled("demo", 1L, null)).isFalse();
    enablement.setForTenant("demo", 1L, true, "admin");
    assertThat(enablement.isEnabled("demo", 1L, null)).isTrue();
    assertThat(enablement.isEnabled("demo", 2L, null)).isFalse();
    assertThat(enablement.isEnabled("demo", null, null)).isFalse();
  }

  @Test
  void agencyOverridesOnlyApplyWithinAnEnabledTenant() {
    enablement.setForAgency("demo", 1L, 10L, true, "admin");
    assertThat(enablement.isEnabled("demo", 1L, 10L)).isFalse();

    enablement.setForTenant("demo", 1L, true, "admin");
    assertThat(enablement.isEnabled("demo", 1L, 10L)).isTrue();
    assertThat(enablement.isEnabled("demo", 1L, 11L)).isTrue();

    enablement.setForAgency("demo", 1L, 11L, false, "admin");
    assertThat(enablement.isEnabled("demo", 1L, 11L)).isFalse();

    enablement.clearAgencyOverride("demo", 1L, 11L);
    assertThat(enablement.isEnabled("demo", 1L, 11L)).isTrue();
  }

  @Test
  void switchingTwiceUpdatesTheSameRow() {
    enablement.setForTenant("demo", 1L, true, "a");
    enablement.setForTenant("demo", 1L, false, "b");
    assertThat(repository.findByAddonName("demo")).singleElement().satisfies(row -> {
      assertThat(row.isEnabled()).isFalse();
      assertThat(row.getUpdatedBy()).isEqualTo("b");
    });
  }

  @Test
  void isOffWithoutAModeAndInModeOff() {
    assertThat(enablement.tenantMode("demo", 1L)).isEqualTo(TenantMode.OFF);
    assertThat(enablement.isEnabled("demo", 1L, null)).isFalse();
    enablement.setForAgency("demo", 1L, 10L, true, "admin");
    enablement.setMode("demo", 1L, TenantMode.OFF, "root");
    assertThat(enablement.isEnabled("demo", 1L, 10L)).isFalse();
  }

  @Test
  void onHiddenIsOnForEveryAgencyWhateverTheOverrides() {
    enablement.setForAgency("demo", 1L, 10L, false, "admin");
    enablement.setMode("demo", 1L, TenantMode.ON_HIDDEN, "root");
    assertThat(enablement.isEnabled("demo", 1L, null)).isTrue();
    assertThat(enablement.isEnabled("demo", 1L, 10L)).isTrue();
    assertThat(enablement.isVisibleToTenantAdmins("demo", 1L)).isFalse();

    enablement.setGlobal("demo", false, "root");
    assertThat(enablement.isEnabled("demo", 1L, 10L)).isFalse();
  }

  @Test
  void onAppliesAgencyOverrides() {
    enablement.setMode("demo", 1L, TenantMode.ON, "root");
    enablement.setForAgency("demo", 1L, 10L, false, "admin");
    assertThat(enablement.isEnabled("demo", 1L, 10L)).isFalse();
    assertThat(enablement.isEnabled("demo", 1L, 11L)).isTrue();
    assertThat(enablement.isVisibleToTenantAdmins("demo", 1L)).isTrue();
  }

  @Test
  void inModeTenantTheTraegerSwitchDecidesAndAgencyOverridesApply() {
    enablement.setMode("demo", 1L, TenantMode.TENANT, "root");
    assertThat(enablement.isEnabled("demo", 1L, null)).isFalse();
    assertThat(enablement.isVisibleToTenantAdmins("demo", 1L)).isTrue();

    enablement.setForTenant("demo", 1L, true, "admin");
    assertThat(enablement.tenantMode("demo", 1L)).isEqualTo(TenantMode.TENANT);
    assertThat(enablement.isEnabled("demo", 1L, null)).isTrue();

    enablement.setForAgency("demo", 1L, 10L, false, "admin");
    assertThat(enablement.isEnabled("demo", 1L, 10L)).isFalse();
  }

  @Test
  void switchingToTenantKeepsWhetherTheAddonIsOn() {
    enablement.setMode("demo", 1L, TenantMode.ON, "root");
    enablement.setMode("demo", 1L, TenantMode.TENANT, "root");
    assertThat(enablement.isEnabled("demo", 1L, null)).isTrue();

    enablement.setMode("demo", 2L, TenantMode.TENANT, "root");
    assertThat(enablement.isEnabled("demo", 2L, null)).isFalse();
  }

  @Test
  void theTraegerSwitchOfASuperAdminOutsideModeTenantSetsOnOrOff() {
    enablement.setForTenant("demo", 1L, true, "root");
    assertThat(enablement.tenantMode("demo", 1L)).isEqualTo(TenantMode.ON);
    enablement.setForTenant("demo", 1L, false, "root");
    assertThat(enablement.tenantMode("demo", 1L)).isEqualTo(TenantMode.OFF);

    enablement.setMode("demo", 1L, TenantMode.ON_HIDDEN, "root");
    enablement.setForTenant("demo", 1L, true, "root");
    assertThat(enablement.tenantMode("demo", 1L)).isEqualTo(TenantMode.ON_HIDDEN);
  }

  @Test
  void traegerRowsWithoutAModeCountAsOnOrOffByTheirFlag() {
    var on = new AddonEnablement("demo", 1L, null);
    on.set(true, java.time.Instant.EPOCH, "migration");
    assertThat(on.getMode()).isEqualTo(TenantMode.ON);
    var off = new AddonEnablement("demo", 2L, null);
    off.set(false, java.time.Instant.EPOCH, "migration");
    assertThat(off.getMode()).isEqualTo(TenantMode.OFF);
    assertThat(new AddonEnablement("demo", 1L, 10L).getMode()).isNull();
  }
}
