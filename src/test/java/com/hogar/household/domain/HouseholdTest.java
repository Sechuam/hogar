package com.hogar.household.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class HouseholdTest {

    private static final Instant NOW = Instant.parse("2026-01-10T10:00:00Z");

    @Test
    void appliesDefaults() {
        Household household = Household.create("  Piso Lavapiés ", null, null, NOW);

        assertThat(household.getName()).isEqualTo("Piso Lavapiés");
        assertThat(household.getSplitPolicy()).isEqualTo(SplitPolicy.EQUAL);
        assertThat(household.getPlan()).isEqualTo(HouseholdPlan.FREE);
        assertThat(household.getCurrency()).isEqualTo("EUR");
        assertThat(household.getCreatedAt()).isEqualTo(NOW);
        assertThat(household.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void validatesNameAndCurrency() {
        assertThatThrownBy(() -> Household.create(" ", null, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Household.create("Piso", null, "eur", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mutatorsTouchUpdatedAt() {
        Household household = Household.create("Piso", SplitPolicy.EQUAL, "USD", NOW);

        household.rename("Casa", NOW.plusSeconds(1));
        assertThat(household.getName()).isEqualTo("Casa");
        assertThat(household.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));

        household.changeSplitPolicy(SplitPolicy.PROPORTIONAL, NOW.plusSeconds(2));
        assertThat(household.getSplitPolicy()).isEqualTo(SplitPolicy.PROPORTIONAL);

        household.changePlan(HouseholdPlan.PREMIUM, NOW.plusSeconds(3));
        assertThat(household.getPlan()).isEqualTo(HouseholdPlan.PREMIUM);
        assertThat(household.getUpdatedAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(household.getCurrency()).isEqualTo("USD");
    }
}
