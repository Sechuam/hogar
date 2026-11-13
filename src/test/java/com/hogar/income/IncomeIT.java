package com.hogar.income;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.expense.FinanceApiSupport;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.income.dto.IncomeRequest;
import com.hogar.income.dto.IncomeResponse;
import com.hogar.income.dto.MonthlyIncomeSummary;
import com.hogar.income.dto.MonthlyIncomeSummary.MemberIncome;
import com.hogar.shared.web.PageResponse;
import com.hogar.support.TestUsers.TestUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class IncomeIT extends FinanceApiSupport {

    private static final LocalDate MARCH = LocalDate.of(2026, 3, 1);

    @Test
    void createReturnsLocationAndBelongsToTheAuthenticatedUser() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, SplitPolicy.PROPORTIONAL);
        join(ana, id, luis);

        ResponseEntity<String> response = call(HttpMethod.POST, incomesPath(id), luis,
                new IncomeRequest(new BigDecimal("1800.5"), "  Nómina  ", MARCH, true, LocalDate.of(2026, 12, 31)));

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        IncomeResponse created = read(response, IncomeResponse.class);
        assertThat(response.getHeaders().getLocation().getPath()).isEqualTo(incomePath(id, created.id()));
        assertThat(created.userId()).isEqualTo(luis.id());
        assertThat(created.amount().toPlainString()).isEqualTo("1800.50");
        assertThat(created.currency()).isEqualTo("EUR");
        assertThat(created.concept()).isEqualTo("Nómina");
        assertThat(created.recurring()).isTrue();
        assertThat(created.endDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        // Cualquier miembro lo ve (transparencia del reparto).
        IncomeResponse fetched = read(get(incomePath(id, created.id()), ana), IncomeResponse.class);
        assertThat(fetched).usingRecursiveComparison().ignoringFields("createdAt").isEqualTo(created);
    }

    @Test
    void onlyTheOwnerOfTheIncomeCanUpdateOrDeleteIt() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, id, luis);
        IncomeResponse lusIncome = oneOffIncome(luis, id, "1000.00", MARCH);
        String path = incomePath(id, lusIncome.id());
        IncomeRequest change = new IncomeRequest(new BigDecimal("1"), "Hackeado", MARCH, false, null);

        // Ni siquiera el OWNER del hogar.
        assertProblem(call(HttpMethod.PUT, path, ana, change), HttpStatus.FORBIDDEN, "income-owner-required");
        assertProblem(call(HttpMethod.DELETE, path, ana, null), HttpStatus.FORBIDDEN, "income-owner-required");
        assertThat(read(get(path, ana), IncomeResponse.class).amount().toPlainString()).isEqualTo("1000.00");

        ResponseEntity<String> updated = call(HttpMethod.PUT, path, luis,
                new IncomeRequest(new BigDecimal("1200"), "Nómina con subida", MARCH, true, null));
        assertThat(updated.getStatusCode()).as(updated.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(read(updated, IncomeResponse.class).amount().toPlainString()).isEqualTo("1200.00");
        assertThat(read(get(path, ana), IncomeResponse.class).concept()).isEqualTo("Nómina con subida");

        assertThat(call(HttpMethod.DELETE, path, luis, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertProblem(get(path, luis), HttpStatus.NOT_FOUND, "income-not-found");
    }

    @Test
    void listShowsEveryMembersIncomesWithMonthAndUserFilters() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, SplitPolicy.PROPORTIONAL);
        join(ana, id, luis);
        UUID salary = createIncome(ana, id, new IncomeRequest(new BigDecimal("2000"), "Nómina",
                LocalDate.of(2026, 1, 1), true, null)).id();
        UUID bonus = oneOffIncome(ana, id, "300", LocalDate.of(2026, 3, 20)).id();
        UUID ended = createIncome(luis, id, new IncomeRequest(new BigDecimal("900"), "Beca",
                LocalDate.of(2025, 9, 1), true, LocalDate.of(2026, 2, 28))).id();
        UUID march = oneOffIncome(luis, id, "100", LocalDate.of(2026, 3, 2)).id();

        assertThat(ids(listIncomes(luis, id, ""))).containsExactly(bonus, march, salary, ended);
        assertThat(ids(listIncomes(luis, id, "?month=2026-03"))).containsExactly(bonus, march, salary);
        assertThat(ids(listIncomes(luis, id, "?month=2026-02"))).containsExactly(salary, ended);
        assertThat(ids(listIncomes(luis, id, "?month=2025-12&userId=" + luis.id()))).containsExactly(ended);
        assertThat(ids(listIncomes(ana, id, "?userId=" + ana.id()))).containsExactly(bonus, salary);
        PageResponse<IncomeResponse> page = listIncomes(ana, id, "?page=1&size=3");
        assertThat(ids(page)).containsExactly(ended);
        assertThat(page.totalElements()).isEqualTo(4);
        assertThat(page.totalPages()).isEqualTo(2);
    }

    @Test
    void summaryHasEveryActiveMemberAndPercentagesAddUpTo100() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser eva = users().create("Eva");
        TestUser gone = users().create("Gone");
        UUID id = createHousehold(ana, SplitPolicy.PROPORTIONAL);
        join(ana, id, luis, eva, gone);
        createIncome(ana, id, new IncomeRequest(new BigDecimal("1000"), "Nómina", LocalDate.of(2026, 1, 1), true,
                null));
        oneOffIncome(luis, id, "1000", LocalDate.of(2026, 3, 31));
        oneOffIncome(luis, id, "1000", LocalDate.of(2026, 4, 1)); // otro mes
        oneOffIncome(eva, id, "1000", LocalDate.of(2026, 3, 15));
        oneOffIncome(gone, id, "9000", LocalDate.of(2026, 3, 15));
        assertThat(call(HttpMethod.POST, householdPath(id) + "/leave", gone, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> response = get(incomesPath(id) + "/summary?month=2026-03", luis);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(tree(response).path("month").asText()).isEqualTo("2026-03");
        MonthlyIncomeSummary summary = read(response, MonthlyIncomeSummary.class);
        assertThat(summary.month()).isEqualTo(YearMonth.of(2026, 3));
        assertThat(summary.currency()).isEqualTo("EUR");
        assertThat(summary.total().toPlainString()).isEqualTo("3000.00");
        assertThat(summary.members()).extracting(MemberIncome::userId)
                .containsExactly(ana.id(), luis.id(), eva.id());
        assertThat(summary.members()).extracting(m -> m.percentage().toPlainString())
                .containsExactly("33.34", "33.33", "33.33");

        MonthlyIncomeSummary may = read(get(incomesPath(id) + "/summary?month=2026-05", ana),
                MonthlyIncomeSummary.class);
        assertThat(may.members()).extracting(m -> m.amount().toPlainString()).containsExactly("1000.00", "0.00",
                "0.00");
        assertThat(may.members()).extracting(m -> m.percentage().toPlainString()).containsExactly("100.00", "0.00",
                "0.00");

        MonthlyIncomeSummary empty = read(get(incomesPath(id) + "/summary?month=2025-12", ana),
                MonthlyIncomeSummary.class);
        assertThat(empty.total().toPlainString()).isEqualTo("0.00");
        assertThat(empty.members()).hasSize(3).extracting(m -> m.percentage().toPlainString()).containsOnly("0.00");
    }

    @Test
    void strangerGetsHouseholdNotFoundEverywhere() {
        TestUser ana = users().create("Ana");
        UUID id = createHousehold(ana, SplitPolicy.EQUAL);
        IncomeResponse income = oneOffIncome(ana, id, "1000", MARCH);
        TestUser stranger = users().create("Extraño");
        String path = incomePath(id, income.id());
        IncomeRequest body = new IncomeRequest(new BigDecimal("1"), "x", MARCH, false, null);

        assertProblem(call(HttpMethod.POST, incomesPath(id), stranger, body), HttpStatus.NOT_FOUND,
                "household-not-found");
        assertProblem(get(incomesPath(id), stranger), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(get(incomesPath(id) + "/summary?month=2026-03", stranger), HttpStatus.NOT_FOUND,
                "household-not-found");
        assertProblem(get(path, stranger), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(call(HttpMethod.PUT, path, stranger, body), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(call(HttpMethod.DELETE, path, stranger, null), HttpStatus.NOT_FOUND, "household-not-found");
        assertThat(listIncomes(ana, id, "").totalElements()).isEqualTo(1);
    }

    @Test
    void incomeOfAnotherHouseholdIsNotFoundThroughMyHousehold() {
        TestUser ana = users().create("Ana");
        UUID theirs = createHousehold(ana, SplitPolicy.EQUAL);
        IncomeResponse foreign = oneOffIncome(ana, theirs, "1000", MARCH);
        // Incluso para el propio dueño del ingreso, si lo pide a través de otro hogar suyo.
        UUID mine = createHousehold(ana, SplitPolicy.EQUAL);
        String path = incomePath(mine, foreign.id());
        IncomeRequest body = new IncomeRequest(new BigDecimal("1"), "x", MARCH, false, null);

        assertProblem(get(path, ana), HttpStatus.NOT_FOUND, "income-not-found");
        assertProblem(call(HttpMethod.PUT, path, ana, body), HttpStatus.NOT_FOUND, "income-not-found");
        assertProblem(call(HttpMethod.DELETE, path, ana, null), HttpStatus.NOT_FOUND, "income-not-found");
        assertThat(listIncomes(ana, mine, "").content()).isEmpty();
        assertThat(read(get(incomePath(theirs, foreign.id()), ana), IncomeResponse.class).amount().toPlainString())
                .isEqualTo("1000.00");
    }

    @Test
    void invalidRequestsAreBadRequest() {
        TestUser ana = users().create("Ana");
        UUID id = createHousehold(ana, SplitPolicy.EQUAL);
        String path = incomesPath(id);

        assertValidationError(call(HttpMethod.POST, path, ana,
                new IncomeRequest(new BigDecimal("0"), "x", MARCH, false, null)), "amount");
        assertValidationError(call(HttpMethod.POST, path, ana,
                new IncomeRequest(new BigDecimal("10.001"), "x", MARCH, false, null)), "amount");
        assertValidationError(call(HttpMethod.POST, path, ana,
                new IncomeRequest(new BigDecimal("10"), "x", MARCH, false, MARCH.plusMonths(1))), "endDateValid");
        assertValidationError(call(HttpMethod.POST, path, ana,
                new IncomeRequest(new BigDecimal("10"), "x", MARCH, true, MARCH.minusDays(1))), "endDateValid");
        assertValidationError(call(HttpMethod.POST, path, ana,
                Map.of("amount", 10, "concept", " ", "date", "2026-03-01")), "concept");
        assertValidationError(call(HttpMethod.POST, path, ana, Map.of("amount", 10, "concept", "x")), "date");

        for (String month : List.of("2026-13", "2026-3", "marzo", "2026-03-01")) {
            assertProblem(get(path + "/summary?month=" + month, ana), HttpStatus.BAD_REQUEST, "bad-request");
            assertProblem(get(path + "?month=" + month, ana), HttpStatus.BAD_REQUEST, "bad-request");
        }
        assertProblem(get(path + "/summary", ana), HttpStatus.BAD_REQUEST, "bad-request");
        assertProblem(get(path + "?size=101", ana), HttpStatus.BAD_REQUEST, "bad-request");
        assertProblem(get(path + "?userId=nadie", ana), HttpStatus.BAD_REQUEST, "bad-request");
        assertThat(listIncomes(ana, id, "").totalElements()).isZero();
    }

    private static List<UUID> ids(PageResponse<IncomeResponse> page) {
        return page.content().stream().map(IncomeResponse::id).toList();
    }
}
