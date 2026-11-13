package com.hogar.expense.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.hogar.expense.domain.ExpenseCategory;
import com.hogar.expense.domain.ExpenseShare;
import com.hogar.expense.domain.SharedExpense;
import com.hogar.expense.domain.split.ShareAllocation;
import com.hogar.expense.domain.split.SplitResult;
import com.hogar.expense.domain.split.SplitWarning;
import com.hogar.household.domain.Household;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.household.repository.HouseholdRepository;
import com.hogar.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnitUtil;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class SharedExpenseRepositoryIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 3, 5);

    @Autowired
    private SharedExpenseRepository expenses;

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
    void persistsAndLoadsWithSharesInTheSameOrderAsInMemory() {
        SharedExpense created = expenses.saveAndFlush(expense(householdId, ana, "30", DATE,
                ExpenseCategory.RENT, equalSplit("30", ana, bob, carla)));
        List<UUID> inMemoryOrder = created.getShares().stream().map(ExpenseShare::getUserId).toList();
        em.clear();

        SharedExpense loaded = expenses.findByIdAndHouseholdId(created.getId(), householdId).orElseThrow();

        assertThat(persistenceUnitUtil().isLoaded(loaded, "shares")).isTrue();
        assertThat(loaded.getShares()).extracting(ExpenseShare::getUserId).containsExactlyElementsOf(inMemoryOrder);
        assertThat(loaded.getShares()).extracting(ExpenseShare::getAmount).containsOnly(new BigDecimal("10.00"));
        assertThat(loaded.getShares()).extracting(ExpenseShare::getWeight).containsOnly(new BigDecimal("1.00"));
        assertThat(loaded.getAmount()).isEqualTo(new BigDecimal("30.00"));
        assertThat(loaded.getCurrency()).isEqualTo("EUR");
        assertThat(loaded.getCategory()).isEqualTo(ExpenseCategory.RENT);
        assertThat(loaded.getExpenseDate()).isEqualTo(DATE);
        assertThat(loaded.getPaidBy()).isEqualTo(ana);
        assertThat(loaded.getCreatedBy()).isEqualTo(ana);
        assertThat(loaded.getSplitPolicyApplied()).isEqualTo(SplitPolicy.EQUAL);
        assertThat(loaded.isSplitFallback()).isFalse();
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(loaded.getVersion()).isZero();
        assertThat(expenses.findByIdAndHouseholdId(created.getId(), otherHouseholdId)).isEmpty();
    }

    @Test
    void persistsFallbackFlag() {
        SplitResult fallback = new SplitResult(SplitPolicy.EQUAL,
                List.of(share(ana, "5", "1"), share(bob, "5", "1")),
                Set.of(SplitWarning.PROPORTIONAL_FALLBACK_NO_INCOME));
        SharedExpense created = expenses.saveAndFlush(expense(householdId, ana, "10", DATE,
                ExpenseCategory.OTHER, fallback));
        em.clear();

        assertThat(expenses.findById(created.getId()).orElseThrow().isSplitFallback()).isTrue();
    }

    @Test
    void replacingSharesForTheSameUsersRespectsUniquenessAtCommit() {
        SharedExpense expense = expenses.saveAndFlush(expense(householdId, ana, "10", DATE,
                ExpenseCategory.GROCERIES, equalSplit("10", ana, bob)));
        List<UUID> oldIds = expense.getShares().stream().map(ExpenseShare::getId).toList();

        expense.changeAmountDatePayer(new BigDecimal("30"), DATE, bob, NOW.plusSeconds(60));
        expense.replaceShares(new SplitResult(SplitPolicy.PROPORTIONAL,
                List.of(share(ana, "20", "2000"), share(bob, "10", "1000")), Set.of()), NOW.plusSeconds(60));
        // save() sobre un agregado gestionado: merge en cascada de las partes nuevas.
        expenses.saveAndFlush(expense);
        checkDeferredConstraintsNow();
        em.clear();

        SharedExpense reloaded = expenses.findByIdAndHouseholdId(expense.getId(), householdId).orElseThrow();
        assertThat(reloaded.getShares()).extracting(ExpenseShare::getId).doesNotContainAnyElementsOf(oldIds);
        assertThat(reloaded.getShares()).filteredOn(s -> s.getUserId().equals(ana))
                .extracting(ExpenseShare::getAmount, ExpenseShare::getWeight)
                .containsExactly(tuple(new BigDecimal("20.00"), new BigDecimal("2000.00")));
        assertThat(reloaded.getSplitPolicyApplied()).isEqualTo(SplitPolicy.PROPORTIONAL);
        assertThat(reloaded.getVersion()).isEqualTo(1);
        assertThat(shareRows(expense.getId())).isEqualTo(2);
    }

    @Test
    void orphanSharesAreRemovedWhenParticipantsChange() {
        SharedExpense expense = expenses.saveAndFlush(expense(householdId, ana, "10", DATE,
                ExpenseCategory.GROCERIES, equalSplit("10", ana, bob)));

        // Sin save(): el agregado gestionado se sincroniza al hacer flush.
        expense.replaceShares(equalSplit("10", carla), NOW.plusSeconds(1));
        em.flush();
        em.clear();

        assertThat(expenses.findByIdAndHouseholdId(expense.getId(), householdId).orElseThrow().getShares())
                .extracting(ExpenseShare::getUserId).containsExactly(carla);
        assertThat(shareRows(expense.getId())).isEqualTo(1);
    }

    @Test
    void recalculatingTwiceWithoutSaveKeepsOneSharePerUser() {
        SharedExpense expense = expenses.saveAndFlush(expense(householdId, ana, "10", DATE,
                ExpenseCategory.GROCERIES, equalSplit("10", ana, bob)));

        expense.replaceShares(equalSplit("10", ana, bob), NOW.plusSeconds(1));
        em.flush();
        expense.replaceShares(equalSplit("10", bob, ana), NOW.plusSeconds(2));
        em.flush();
        checkDeferredConstraintsNow();

        assertThat(shareRows(expense.getId())).isEqualTo(2);
    }

    @Test
    void deletingTheExpenseCascadesToShares() {
        SharedExpense viaJpa = expenses.saveAndFlush(expense(householdId, ana, "10", DATE,
                ExpenseCategory.GROCERIES, equalSplit("10", ana, bob)));
        SharedExpense viaSql = expenses.saveAndFlush(expense(householdId, ana, "10", DATE,
                ExpenseCategory.GROCERIES, equalSplit("10", ana, bob)));
        em.clear();

        expenses.delete(expenses.findById(viaJpa.getId()).orElseThrow());
        expenses.flush();
        jdbc.update("delete from shared_expenses where id = ?", viaSql.getId());

        assertThat(shareRows(viaJpa.getId())).isZero();
        assertThat(shareRows(viaSql.getId())).isZero();
    }

    @Test
    void oneSharePerUserAndExpense() {
        SharedExpense expense = expenses.saveAndFlush(expense(householdId, ana, "10", DATE,
                ExpenseCategory.GROCERIES, equalSplit("10", ana)));

        jdbc.update("insert into expense_shares values (?, ?, ?, 0, 1)", UUID.randomUUID(), expense.getId(), ana);

        // La restricción es diferida: se comprueba al hacer commit o al pedirla inmediata.
        assertThatThrownBy(this::checkDeferredConstraintsNow)
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "update shared_expenses set amount = 0 where id = ?",
            "update shared_expenses set currency = 'eur' where id = ?",
            "update shared_expenses set category = 'CAR' where id = ?",
            "update shared_expenses set split_policy_applied = 'RANDOM' where id = ?",
            "update shared_expenses set split_policy_applied = 'PROPORTIONAL', split_fallback = true where id = ?",
            "update expense_shares set amount = -0.01 where expense_id = ?",
            "update expense_shares set weight = -1 where expense_id = ?",
            "update expense_shares set user_id = gen_random_uuid() where expense_id = ?"
    })
    void databaseChecksRejectInvalidRows(String update) {
        SharedExpense expense = expenses.saveAndFlush(expense(householdId, ana, "10", DATE,
                ExpenseCategory.GROCERIES, equalSplit("10", ana)));

        assertThatThrownBy(() -> jdbc.update(update, expense.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void categoryCheckAcceptsEveryEnumValue() {
        SharedExpense expense = expenses.saveAndFlush(expense(householdId, ana, "10", DATE,
                ExpenseCategory.GROCERIES, equalSplit("10", ana)));

        for (ExpenseCategory category : ExpenseCategory.values()) {
            assertThat(jdbc.update("update shared_expenses set category = ? where id = ?",
                    category.name(), expense.getId())).isEqualTo(1);
        }
    }

    @Test
    void searchFiltersPaginatesAndOrdersByDateThenCreation() {
        SharedExpense rentMarch = expenses.save(expense(householdId, ana, "900", LocalDate.of(2026, 3, 1),
                ExpenseCategory.RENT, equalSplit("900", ana, bob)));
        SharedExpense groceriesMarchEarly = expenses.save(expense(householdId, bob, "40", LocalDate.of(2026, 3, 20),
                ExpenseCategory.GROCERIES, equalSplit("40", ana, bob)));
        SharedExpense groceriesMarchLate = expenses.save(SharedExpense.create(householdId, ana, ana,
                new BigDecimal("30"), "EUR", "Más compra", ExpenseCategory.GROCERIES, LocalDate.of(2026, 3, 20),
                equalSplit("30", ana, bob), NOW.plusSeconds(60)));
        SharedExpense rentApril = expenses.save(expense(householdId, ana, "900", LocalDate.of(2026, 4, 1),
                ExpenseCategory.RENT, equalSplit("900", ana, bob)));
        SharedExpense februaryLight = expenses.save(expense(householdId, bob, "60", LocalDate.of(2026, 2, 28),
                ExpenseCategory.ELECTRICITY, equalSplit("60", ana, bob)));
        expenses.save(expense(otherHouseholdId, ana, "10", LocalDate.of(2026, 3, 5),
                ExpenseCategory.RENT, equalSplit("10", ana)));
        expenses.flush();
        em.clear();

        // El orden pedido en el Pageable se ignora: lo fija el contrato.
        Page<SharedExpense> page0 = expenses.search(householdId, null, null, null, null,
                PageRequest.of(0, 3, Sort.by("amount")));
        Page<SharedExpense> page1 = expenses.search(householdId, null, null, null, null, PageRequest.of(1, 3));
        assertThat(page0.getTotalElements()).isEqualTo(5);
        assertThat(page0.getContent()).containsExactly(rentApril, groceriesMarchLate, groceriesMarchEarly);
        assertThat(page1.getContent()).containsExactly(rentMarch, februaryLight);

        assertThat(expenses.search(householdId, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), null, null,
                PageRequest.of(0, 10)).getContent())
                .containsExactly(groceriesMarchLate, groceriesMarchEarly, rentMarch);
        assertThat(expenses.search(householdId, LocalDate.of(2026, 3, 2), null, ExpenseCategory.RENT, null,
                PageRequest.of(0, 10)).getContent()).containsExactly(rentApril);
        assertThat(expenses.search(householdId, null, LocalDate.of(2026, 3, 20), null, bob,
                PageRequest.of(0, 10)).getContent()).containsExactly(groceriesMarchEarly, februaryLight);
        assertThat(expenses.search(householdId, null, null, ExpenseCategory.WATER, null,
                PageRequest.of(0, 10)).getTotalElements()).isZero();
    }

    @Test
    void sharesOfAPageAreLoadedInBatchOnFirstAccess() {
        for (int i = 0; i < 3; i++) {
            expenses.save(expense(householdId, ana, "10", DATE.plusDays(i), ExpenseCategory.OTHER,
                    equalSplit("10", ana, bob)));
        }
        expenses.flush();
        em.clear();

        List<SharedExpense> page = expenses.search(householdId, null, null, null, null, PageRequest.of(0, 10))
                .getContent();
        assertThat(page).hasSize(3).allSatisfy(e -> assertThat(persistenceUnitUtil().isLoaded(e, "shares")).isFalse());

        assertThat(page.get(0).getShares()).hasSize(2);

        // @BatchSize: la primera colección accedida inicializa las de toda la página en la misma consulta.
        assertThat(page).allSatisfy(e -> assertThat(persistenceUnitUtil().isLoaded(e, "shares")).isTrue());
    }

    private SharedExpense expense(UUID household, UUID paidBy, String amount, LocalDate date, ExpenseCategory category,
            SplitResult split) {
        return SharedExpense.create(household, paidBy, paidBy, new BigDecimal(amount), "EUR", "Gasto " + amount,
                category, date, split, NOW);
    }

    /** Reparto a partes iguales de un importe divisible exactamente entre los usuarios. */
    private static SplitResult equalSplit(String amount, UUID... users) {
        BigDecimal each = new BigDecimal(amount).divide(BigDecimal.valueOf(users.length));
        return new SplitResult(SplitPolicy.EQUAL,
                Arrays.stream(users).map(u -> new ShareAllocation(u, each, BigDecimal.ONE)).toList(),
                Set.of());
    }

    private static ShareAllocation share(UUID userId, String amount, String weight) {
        return new ShareAllocation(userId, new BigDecimal(amount), new BigDecimal(weight));
    }

    /** Evalúa ya las restricciones diferidas (en un test @Transactional el commit nunca llega). */
    private void checkDeferredConstraintsNow() {
        jdbc.execute("set constraints uk_expense_shares_expense_user immediate");
    }

    private int shareRows(UUID expenseId) {
        return jdbc.queryForObject("select count(*) from expense_shares where expense_id = ?", Integer.class,
                expenseId);
    }

    private PersistenceUnitUtil persistenceUnitUtil() {
        return em.getEntityManagerFactory().getPersistenceUnitUtil();
    }
}
