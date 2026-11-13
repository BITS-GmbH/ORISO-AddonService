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
}
