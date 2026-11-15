package com.hogar.household.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hogar.household.config.PlanProperties;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class PlanLimitsTest {

    private static final PlanLimits FREE = new PlanProperties(4, 1, DataSize.ofMegabytes(50), 12).freeLimits();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    @Test
    void freeLimitsComeFromTheProperties() {
        assertThat(FREE).isEqualTo(new PlanLimits(4, 1, 52_428_800L, 12));
    }

    @Test
    void limitsAreInclusiveAndNullMeansUnlimited() {
        assertThat(FREE.allowsMembers(4)).isTrue();
        assertThat(FREE.allowsMembers(5)).isFalse();
        assertThat(FREE.allowsStorage(52_428_800L)).isTrue();
        assertThat(FREE.allowsStorage(52_428_801L)).isFalse();
        assertThat(FREE.allowsAnotherOwnedHousehold(0)).isTrue();
        assertThat(FREE.allowsAnotherOwnedHousehold(1)).isFalse();

        assertThat(PlanLimits.UNLIMITED.allowsMembers(Long.MAX_VALUE)).isTrue();
        assertThat(PlanLimits.UNLIMITED.allowsStorage(Long.MAX_VALUE)).isTrue();
        assertThat(PlanLimits.UNLIMITED.allowsAnotherOwnedHousehold(Long.MAX_VALUE)).isTrue();
    }

    @Test
    void historyCoversTheCurrentMonthAndTheElevenBefore() {
        assertThat(FREE.historyStart(TODAY)).contains(LocalDate.of(2025, 12, 1));
        assertThat(FREE.historyStart(LocalDate.of(2026, 1, 1))).contains(LocalDate.of(2025, 2, 1));
        assertThat(new PlanLimits(null, null, null, 1).historyStart(TODAY)).contains(LocalDate.of(2026, 11, 1));
        assertThat(PlanLimits.UNLIMITED.historyStart(TODAY)).isEmpty();
    }

    @Test
    void aWindowReachingBeforeYearOneDoesNotLimitAnything() {
        // 2026-11 está a 24 310 meses de 0001-01: una ventana de 24 311 meses empieza justo ahí.
        assertThat(new PlanLimits(null, null, null, 24_311).historyStart(TODAY)).contains(LocalDate.of(1, 1, 1));
        assertThat(new PlanLimits(null, null, null, 24_312).historyStart(TODAY)).isEmpty();
        assertThat(new PlanLimits(null, null, null, Integer.MAX_VALUE).visibleFrom(null, TODAY)).isNull();
    }

    @Test
    void visibleFromIsTheLaterOfRequestedAndHistoryStart() {
        LocalDate start = LocalDate.of(2025, 12, 1);
        assertThat(FREE.visibleFrom(null, TODAY)).isEqualTo(start);
        assertThat(FREE.visibleFrom(LocalDate.of(2020, 1, 1), TODAY)).isEqualTo(start);
        assertThat(FREE.visibleFrom(start, TODAY)).isEqualTo(start);
        assertThat(FREE.visibleFrom(LocalDate.of(2026, 3, 1), TODAY)).isEqualTo(LocalDate.of(2026, 3, 1));

        assertThat(PlanLimits.UNLIMITED.visibleFrom(null, TODAY)).isNull();
        assertThat(PlanLimits.UNLIMITED.visibleFrom(start, TODAY)).isEqualTo(start);
    }

    @Test
    void propertiesRejectNonPositiveLimits() {
        assertThatThrownBy(() -> new PlanProperties(0, 1, DataSize.ofMegabytes(1), 12))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanProperties(4, 0, DataSize.ofMegabytes(1), 12))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanProperties(4, 1, DataSize.ofMegabytes(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanProperties(4, 1, null, 12))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanProperties(4, 1, DataSize.ofBytes(-1), 12))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
