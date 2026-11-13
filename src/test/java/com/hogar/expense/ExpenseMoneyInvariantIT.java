package com.hogar.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.expense.dto.ExpenseResponse;
import com.hogar.expense.dto.ExpenseResponse.ShareResponse;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.support.TestUsers.TestUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * QA Fase 2: la invariante {@code Σ shares == amount} de punta a punta (respuesta HTTP y BD vía
 * SQL), importes extremos y números "raros" en el JSON, que nunca deben acabar en 500.
 */
class ExpenseMoneyInvariantIT extends FinanceApiSupport {

    private static final LocalDate APRIL = LocalDate.of(2026, 4, 17);

    /** Hogar con {@code n} miembros (el primero es OWNER) en orden de alta. */
    private record Flat(UUID id, List<TestUser> members) {
        TestUser owner() {
            return members.get(0);
        }
    }

    private Flat flat(SplitPolicy policy, int n) {
        List<TestUser> members = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            members.add(users().create("Miembro" + i));
        }
        UUID id = createHousehold(members.get(0), policy);
        join(members.get(0), id, members.subList(1, n).toArray(TestUser[]::new));
        return new Flat(id, members);
    }

    private static BigDecimal sum(ExpenseResponse expense) {
        return expense.shares().stream().map(ShareResponse::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void assertCoherent(ExpenseResponse e, int members) {
        assertThat(sum(e)).as("Σ shares de %s", e.id()).isEqualByComparingTo(e.amount());
        assertThat(e.shares()).hasSize(members);
        assertThat(e.shares()).extracting(ShareResponse::userId).doesNotHaveDuplicates();
        assertThat(e.shares()).allSatisfy(s -> {
            assertThat(s.amount().signum()).isNotNegative();
            assertThat(s.amount().scale()).isEqualTo(2);
        });
        assertThat(dbShares(e.id())).isEqualTo(shares(e));
    }

    @Test
    void manyRandomExpensesKeepTheInvariantInTheResponseAndInTheDatabase() {
        Random random = new Random(20260417L); // determinista: un fallo es reproducible
        for (int members : new int[] {1, 2, 3, 7, 8}) {
            for (SplitPolicy policy : SplitPolicy.values()) {
                Flat f = flat(policy, members);
                if (policy == SplitPolicy.PROPORTIONAL) {
                    // Ingresos con céntimos "feos"; uno de cada tres miembros a 0 (D-009), pero no todos.
                    for (int i = 0; i < members; i++) {
                        if (i % 3 != 2) {
                            long cents = 1 + random.nextInt(500_000_00);
                            oneOffIncome(f.members().get(i), f.id(), BigDecimal.valueOf(cents, 2).toPlainString(),
                                    APRIL.withDayOfMonth(1 + random.nextInt(28)));
                        }
                    }
                }
                for (int k = 0; k < 8; k++) {
                    long cents = switch (k) {
                        case 0 -> 1;
                        case 1 -> members - 1L + 1; // n céntimos
                        case 2 -> 99_999_999_999L;
                        default -> 1 + (long) random.nextInt(1_000_000);
                    };
                    TestUser creator = f.members().get(random.nextInt(members));
                    ExpenseResponse e = createExpense(creator, f.id(),
                            expense(BigDecimal.valueOf(cents, 2).toPlainString(), APRIL, null));
                    assertCoherent(e, members);
                    if (policy == SplitPolicy.PROPORTIONAL) {
                        assertThat(e.warnings()).isEmpty();
                        // Un peso 0 nunca recibe céntimo.
                        assertThat(e.shares()).filteredOn(s -> s.weight().signum() == 0)
                                .allSatisfy(s -> assertThat(s.amount().signum()).isZero());
                    }
                }
                assertDbMoneyInvariant(f.id());
            }
        }
    }

    @Test
    void oneCentAmongEightMembersGoesToTheOldestMember() {
        Flat f = flat(SplitPolicy.EQUAL, 8);

        ExpenseResponse e = createExpense(f.members().get(7), f.id(), expense("0.01", APRIL, null));

        assertCoherent(e, 8);
        assertThat(shares(e)).containsEntry(f.owner().id(), "0.01");
        assertThat(shares(e).values()).filteredOn("0.00"::equals).hasSize(7);
    }

    @Test
    void maximumAmountAmongEightMembersAndMaximumIncomes() {
        Flat equal = flat(SplitPolicy.EQUAL, 8);
        ExpenseResponse e = createExpense(equal.owner(), equal.id(), expense("999999999.99", APRIL, null));
        assertCoherent(e, 8);
        // 99 999 999 999 céntimos / 8 = 12 499 999 999 resto 7: los 7 más antiguos llevan uno más.
        assertThat(shares(e).values()).filteredOn("125000000.00"::equals).hasSize(7);
        assertThat(shares(e).values()).filteredOn("124999999.99"::equals).hasSize(1);

        Flat proportional = flat(SplitPolicy.PROPORTIONAL, 8);
        for (TestUser m : proportional.members()) {
            // Varios ingresos máximos por persona: el peso supera el máximo de un único importe.
            for (int i = 0; i < 3; i++) {
                oneOffIncome(m, proportional.id(), "999999999.99", APRIL);
            }
        }
        ExpenseResponse p = createExpense(proportional.owner(), proportional.id(),
                expense("999999999.99", APRIL, null));
        assertCoherent(p, 8);
        assertThat(p.shares()).extracting(s -> s.weight().toPlainString()).containsOnly("2999999999.97");
        assertDbMoneyInvariant(proportional.id());
    }

    @Test
    void outOfRangeOrTooPreciseAmountsAreValidationErrors() {
        Flat f = flat(SplitPolicy.EQUAL, 2);
        for (String amount : new String[] {"0", "0.00", "-0.01", "-5", "0.001", "10.001", "1000000000.00",
                "1E+9", "1e-3", "1E+400", "1E-400"}) {
            ResponseEntity<String> response = postRaw(f, amount);
            assertValidationError(response, "amount");
        }
        assertThat(listExpenses(f.owner(), f.id(), "").totalElements()).isZero();
    }

    @Test
    void scientificNotationAndTrailingZerosAreNormalisedToTwoDecimals() {
        Flat f = flat(SplitPolicy.EQUAL, 2);
        Map<String, String> accepted = Map.of(
                "1e2", "100.00",
                "1.5E1", "15.00",
                "7", "7.00",
                "999999999.99", "999999999.99");
        accepted.forEach((raw, expected) -> {
            ResponseEntity<String> response = postRaw(f, raw);
            assertThat(response.getStatusCode()).as(raw + " → " + response.getBody()).isEqualTo(HttpStatus.CREATED);
            ExpenseResponse e = read(response, ExpenseResponse.class);
            assertThat(e.amount().toPlainString()).isEqualTo(expected);
            assertCoherent(e, 2);
        });
        assertDbMoneyInvariant(f.id());

        // Ceros a la derecha más allá del céntimo: el dominio (Money) los admitiría, pero @Digits del DTO
        // no los quita y responde 400. Comportamiento definido (no 500), documentado aquí.
        assertValidationError(postRaw(f, "12.340"), "amount");
    }

    @Test
    void amountsAsJsonStringsHaveADefinedBehaviourAndNeverFail() {
        Flat f = flat(SplitPolicy.EQUAL, 2);

        // Jackson admite por defecto un número entre comillas (coerción de escalares): mismo resultado.
        ResponseEntity<String> quoted = postRawJson(f, "\"12.34\"");
        assertThat(quoted.getStatusCode()).as(quoted.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(read(quoted, ExpenseResponse.class).amount().toPlainString()).isEqualTo("12.34");

        // Y entre comillas las mismas reglas de validación.
        assertValidationError(postRawJson(f, "\"12.345\""), "amount");
        for (String bad : new String[] {"\"abc\"", "\"12,34\"", "\"\"", "true", "[1]", "{\"v\":1}", "\"NaN\"",
                "\"Infinity\""}) {
            ResponseEntity<String> response = postRawJson(f, bad);
            assertThat(response.getStatusCode()).as(bad + " → " + response.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        // Tokens no JSON (NaN sin comillas) → JSON mal formado, también 400.
        assertThat(postRawJson(f, "NaN").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(listExpenses(f.owner(), f.id(), "").totalElements()).isEqualTo(1);
    }

    private ResponseEntity<String> postRaw(Flat f, String amountLiteral) {
        return postRawJson(f, amountLiteral);
    }

    private ResponseEntity<String> postRawJson(Flat f, String amountJson) {
        String body = """
                {"amount": %s, "description": "Raro", "category": "GROCERIES", "date": "%s"}
                """.formatted(amountJson, APRIL);
        HttpHeaders headers = f.owner().bearer();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(expensesPath(f.id()), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
