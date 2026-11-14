package com.hogar.settlement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.hogar.household.domain.Household;
import com.hogar.household.repository.HouseholdRepository;
import com.hogar.settlement.domain.Settlement;
import com.hogar.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class SettlementRepositoryIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 3, 5);

    @Autowired
    private SettlementRepository settlements;

    @Autowired
    private HouseholdRepository households;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    private UUID householdId;
    private UUID otherHouseholdId;
    private UUID ana;
    private UUID bob;
    private UUID carla;

    @BeforeEach
    void setUp() {
        householdId = households.saveAndFlush(Household.create("Piso", null, null, NOW)).getId();
        otherHouseholdId = households.saveAndFlush(Household.create("Otro", null, null, NOW)).getId();
        ana = users().create("Ana").id();
        bob = users().create("Bob").id();
        carla = users().create("Carla").id();
    }

    @Test
    void persistsAndFindsByIdAndHousehold() {
        Settlement created = settlements.saveAndFlush(Settlement.create(householdId, ana, bob,
                new BigDecimal("12.5"), "EUR", DATE, " Bizum ñ 😀 ", carla, NOW));
        em.clear();

        Settlement loaded = settlements.findByIdAndHouseholdId(created.getId(), householdId).orElseThrow();

        assertThat(loaded).isEqualTo(created);
        assertThat(loaded.isNew()).isFalse();
        assertThat(loaded.getHouseholdId()).isEqualTo(householdId);
        assertThat(loaded.getFromUserId()).isEqualTo(ana);
        assertThat(loaded.getToUserId()).isEqualTo(bob);
        assertThat(loaded.getAmount()).isEqualTo("12.50");
        assertThat(loaded.getCurrency()).isEqualTo("EUR");
        assertThat(loaded.getDate()).isEqualTo(DATE);
        assertThat(loaded.getNote()).isEqualTo("Bizum ñ 😀");
        assertThat(loaded.getCreatedBy()).isEqualTo(carla);
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void nullNoteAndDateBoundsRoundTrip() {
        Settlement min = settlements.saveAndFlush(settlement(householdId, ana, bob, "1", Settlement.MIN_DATE, NOW));
        Settlement max = settlements.saveAndFlush(settlement(householdId, ana, bob, "1", Settlement.MAX_DATE, NOW));
        em.clear();

        Settlement loadedMin = settlements.findById(min.getId()).orElseThrow();
        assertThat(loadedMin.getDate()).isEqualTo(Settlement.MIN_DATE);
        assertThat(loadedMin.getNote()).isNull();
        assertThat(settlements.findById(max.getId()).orElseThrow().getDate()).isEqualTo(Settlement.MAX_DATE);
    }

    /** {@code varchar(200)} cuenta caracteres (puntos de código), no unidades UTF-16 ni bytes. */
    @Test
    void noteOf200EmojisFitsTheColumn() {
        String note = "😀".repeat(Settlement.MAX_NOTE_LENGTH);
        Settlement created = settlements.saveAndFlush(Settlement.create(householdId, ana, bob, BigDecimal.ONE, "EUR",
                DATE, note, ana, NOW));
        em.clear();

        assertThat(settlements.findById(created.getId()).orElseThrow().getNote()).isEqualTo(note);
    }

    @Test
    void findByIdAndHouseholdIgnoresOtherHouseholds() {
        Settlement created = settlements.saveAndFlush(settlement(householdId, ana, bob, "10", DATE, NOW));

        assertThat(settlements.findByIdAndHouseholdId(created.getId(), otherHouseholdId)).isEmpty();
        assertThat(settlements.findByIdAndHouseholdId(UUID.randomUUID(), householdId)).isEmpty();
    }

    @Test
    void saveOfANewSettlementInsertsWithoutPreviousSelect() {
        Settlement settlement = settlement(householdId, ana, bob, "10", DATE, NOW);

        // Persistable: save() hace persist y devuelve la misma instancia (sin merge).
        assertThat(settlements.saveAndFlush(settlement)).isSameAs(settlement);
        assertThat(settlement.isNew()).isFalse();
    }

    @Test
    void settlementCanBeDeleted() {
        Settlement settlement = settlements.saveAndFlush(settlement(householdId, ana, bob, "10", DATE, NOW));
        em.clear();

        settlements.delete(settlements.findByIdAndHouseholdId(settlement.getId(), householdId).orElseThrow());
        settlements.flush();

        assertThat(jdbc.queryForObject("select count(*) from settlements where id = ?", Integer.class,
                settlement.getId())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "update settlements set amount = 0 where id = ?",
            "update settlements set amount = -1 where id = ?",
            "update settlements set to_user_id = from_user_id where id = ?",
            "update settlements set currency = 'eur' where id = ?",
            "update settlements set currency = 'E1R' where id = ?",
            "update settlements set settlement_date = date '0001-01-01' - 1 where id = ?",
            "update settlements set note = '   ' where id = ?",
            "update settlements set note = '' where id = ?",
            "update settlements set note = repeat('a', 201) where id = ?",
            "update settlements set from_user_id = gen_random_uuid() where id = ?",
            "update settlements set to_user_id = gen_random_uuid() where id = ?",
            "update settlements set created_by = gen_random_uuid() where id = ?",
            "update settlements set household_id = gen_random_uuid() where id = ?"
    })
    void databaseChecksRejectInvalidRows(String update) {
        Settlement settlement = settlements.saveAndFlush(settlement(householdId, ana, bob, "10", DATE, NOW));

        assertThatThrownBy(() -> jdbc.update(update, settlement.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseAcceptsAThirdPartyAsCreatorAndAnyUserAsParty() {
        // Sin FK a memberships: la BD no exige que las partes sean miembros (lo comprueba el servicio).
        Settlement settlement = settlements.saveAndFlush(settlement(householdId, ana, bob, "10", DATE, NOW));

        assertThat(jdbc.update("update settlements set from_user_id = ?, created_by = ? where id = ?",
                carla, carla, settlement.getId())).isEqualTo(1);
    }

    @Test
    void searchOrdersByDateDescCreatedAtDescIdDescIgnoringTheRequestedSort() {
        Settlement older = settlements.save(settlement(householdId, ana, bob, "1", DATE.minusDays(1), NOW));
        Settlement newerDate = settlements.save(settlement(householdId, ana, bob, "2", DATE, NOW));
        Settlement sameDateLaterCreated = settlements.save(
                settlement(householdId, ana, bob, "3", DATE, NOW.plusSeconds(60)));
        settlements.flush();

        Page<Settlement> page = settlements.search(householdId, null, null, null,
                PageRequest.of(0, 10, Sort.by("amount").ascending()));

        assertThat(page.getContent()).containsExactly(sameDateLaterCreated, newerDate, older);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void ties_onDateAndCreatedAt_areBrokenByIdDescendingAndPaginationIsStable() {
        List<Settlement> created = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            created.add(settlement(householdId, ana, bob, "1", DATE, NOW));
        }
        settlements.saveAllAndFlush(created);
        // Mismo orden que uuid en PostgreSQL: comparación como texto (D-041).
        List<UUID> expected = created.stream().map(Settlement::getId)
                .sorted(Comparator.comparing(UUID::toString).reversed()).toList();

        List<UUID> paged = Stream.of(0, 1, 2, 3)
                .flatMap(p -> settlements.search(householdId, null, null, null, PageRequest.of(p, 2))
                        .getContent().stream())
                .map(Settlement::getId)
                .toList();

        assertThat(paged).containsExactlyElementsOf(expected);
    }

    @Test
    void searchFiltersByInclusiveDateRange() {
        Settlement before = settlements.save(settlement(householdId, ana, bob, "1", LocalDate.of(2026, 2, 28), NOW));
        Settlement first = settlements.save(settlement(householdId, ana, bob, "2", LocalDate.of(2026, 3, 1), NOW));
        Settlement last = settlements.save(settlement(householdId, ana, bob, "3", LocalDate.of(2026, 3, 31), NOW));
        Settlement after = settlements.save(settlement(householdId, ana, bob, "4", LocalDate.of(2026, 4, 1), NOW));
        settlements.flush();

        assertThat(search(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), null)).containsExactly(last, first);
        assertThat(search(LocalDate.of(2026, 3, 31), null, null)).containsExactly(after, last);
        assertThat(search(null, LocalDate.of(2026, 3, 1), null)).containsExactly(first, before);
        assertThat(search(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 30), null)).isEmpty();
    }

    @Test
    void searchFiltersByUserAsPayerOrReceiver() {
        Settlement anaToBob = settlements.save(settlement(householdId, ana, bob, "1", DATE.minusDays(2), NOW));
        Settlement bobToCarla = settlements.save(settlement(householdId, bob, carla, "2", DATE.minusDays(1), NOW));
        Settlement carlaToAna = settlements.save(settlement(householdId, carla, ana, "3", DATE, NOW));
        settlements.flush();

        assertThat(search(null, null, ana)).containsExactly(carlaToAna, anaToBob);
        assertThat(search(null, null, bob)).containsExactly(bobToCarla, anaToBob);
        assertThat(search(null, null, carla)).containsExactly(carlaToAna, bobToCarla);
        assertThat(search(null, null, UUID.randomUUID())).isEmpty();
        // Filtros combinados.
        assertThat(search(DATE.minusDays(1), null, ana)).containsExactly(carlaToAna);
    }

    @Test
    void searchOnlyReturnsTheHouseholdsSettlementsAndSupportsUnpaged() {
        Settlement mine = settlements.save(settlement(householdId, ana, bob, "1", DATE, NOW));
        settlements.save(settlement(otherHouseholdId, ana, bob, "1", DATE, NOW));
        settlements.flush();

        assertThat(settlements.search(householdId, null, null, null, Pageable.unpaged()).getContent())
                .containsExactly(mine);
        assertThat(settlements.search(householdId, null, null, ana, PageRequest.of(1, 10)).getContent()).isEmpty();
    }

    @Test
    void sumsAreGroupedByPayerAndByReceiverWithinTheHousehold() {
        settlements.save(settlement(householdId, ana, bob, "10.10", DATE, NOW));
        settlements.save(settlement(householdId, ana, carla, "5", DATE, NOW));
        settlements.save(settlement(householdId, bob, ana, "0.90", DATE, NOW));
        settlements.save(settlement(householdId, carla, bob, "1.01", DATE, NOW));
        // Otro hogar: no cuenta.
        settlements.save(settlement(otherHouseholdId, ana, bob, "1000", DATE, NOW));
        settlements.flush();

        assertThat(settlements.sumSentByUser(householdId))
                .extracting(SettlementUserAmount::getUserId, SettlementUserAmount::getTotal)
                .containsExactlyInAnyOrder(
                        tuple(ana, new BigDecimal("15.10")),
                        tuple(bob, new BigDecimal("0.90")),
                        tuple(carla, new BigDecimal("1.01")));
        assertThat(settlements.sumReceivedByUser(householdId))
                .extracting(SettlementUserAmount::getUserId, SettlementUserAmount::getTotal)
                .containsExactlyInAnyOrder(
                        tuple(bob, new BigDecimal("11.11")),
                        tuple(carla, new BigDecimal("5.00")),
                        tuple(ana, new BigDecimal("0.90")));
    }

    @Test
    void sumsOmitUsersWithoutSettlementsAndAreEmptyForAnEmptyHousehold() {
        settlements.saveAndFlush(settlement(householdId, ana, bob, "3", DATE, NOW));

        assertThat(settlements.sumSentByUser(householdId)).extracting(SettlementUserAmount::getUserId)
                .containsExactly(ana);
        assertThat(settlements.sumReceivedByUser(householdId)).extracting(SettlementUserAmount::getUserId)
                .containsExactly(bob);
        UUID emptyHousehold = households.saveAndFlush(Household.create("Vacío", null, null, NOW)).getId();
        assertThat(settlements.sumSentByUser(emptyHousehold)).isEmpty();
        assertThat(settlements.sumReceivedByUser(emptyHousehold)).isEmpty();
    }

    private List<Settlement> search(LocalDate from, LocalDate to, UUID userId) {
        return settlements.search(householdId, from, to, userId, PageRequest.of(0, 50)).getContent();
    }

    private static Settlement settlement(UUID household, UUID from, UUID to, String amount, LocalDate date,
            Instant createdAt) {
        return Settlement.create(household, from, to, new BigDecimal(amount), "EUR", date, null, from, createdAt);
    }
}
