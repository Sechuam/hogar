package com.hogar.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.hogar.expense.FinanceApiSupport;
import com.hogar.settlement.dto.BalanceResponse;
import com.hogar.settlement.dto.MemberBalance;
import com.hogar.settlement.dto.SettlementResponse;
import com.hogar.settlement.dto.SuggestedTransfer;
import com.hogar.settlement.dto.SuggestedTransfersResponse;
import com.hogar.shared.web.PageResponse;
import com.hogar.support.TestUsers.TestUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Atajos HTTP para los IT de balances y liquidaciones (reutiliza hogares, ingresos y gastos de
 * {@link FinanceApiSupport}). Los cuerpos se envían como mapas: serializar {@code SettlementRequest}
 * añadiría la propiedad {@code distinctParties} de su {@code @AssertTrue}, que no es del contrato.
 */
abstract class SettlementApiSupport extends FinanceApiSupport {

    protected static final LocalDate MARCH_5 = LocalDate.of(2026, 3, 5);

    protected static Map<String, Object> settlementBody(UUID from, UUID to, Object amount, Object date,
                                                        String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fromUserId", from);
        body.put("toUserId", to);
        body.put("amount", amount);
        body.put("date", date == null ? null : date.toString());
        body.put("note", note);
        return body;
    }

    protected ResponseEntity<String> settleCall(TestUser user, UUID householdId, UUID from, UUID to, String amount) {
        return call(HttpMethod.POST, settlementsPath(householdId), user,
                settlementBody(from, to, new BigDecimal(amount), MARCH_5, null));
    }

    protected SettlementResponse settle(TestUser user, UUID householdId, UUID from, UUID to, String amount) {
        return settle(user, householdId, settlementBody(from, to, new BigDecimal(amount), MARCH_5, null));
    }

    protected SettlementResponse settle(TestUser user, UUID householdId, Map<String, Object> body) {
        ResponseEntity<String> response = call(HttpMethod.POST, settlementsPath(householdId), user, body);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        SettlementResponse created = read(response, SettlementResponse.class);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().getPath())
                .isEqualTo(settlementPath(householdId, created.id()));
        return created;
    }

    protected ResponseEntity<String> deleteSettlement(TestUser user, UUID householdId, UUID settlementId) {
        return call(HttpMethod.DELETE, settlementPath(householdId, settlementId), user, null);
    }

    protected BalanceResponse balances(TestUser user, UUID householdId) {
        ResponseEntity<String> response = get(householdPath(householdId) + "/balances", user);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        BalanceResponse balances = read(response, BalanceResponse.class);
        assertThat(balances.members().stream().map(MemberBalance::balance).reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("Σ balances").isEqualByComparingTo("0");
        assertThat(balances.members()).allSatisfy(m -> {
            assertThat(m.paid().scale()).isEqualTo(2);
            assertThat(m.owed().scale()).isEqualTo(2);
            assertThat(m.settlementsSent().scale()).isEqualTo(2);
            assertThat(m.settlementsReceived().scale()).isEqualTo(2);
            assertThat(m.balance().scale()).isEqualTo(2);
        });
        return balances;
    }

    protected SuggestedTransfersResponse suggested(TestUser user, UUID householdId) {
        ResponseEntity<String> response = get(settlementsPath(householdId) + "/suggested", user);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return read(response, SuggestedTransfersResponse.class);
    }

    protected PageResponse<SettlementResponse> listSettlements(TestUser user, UUID householdId, String query) {
        ResponseEntity<String> response = get(settlementsPath(householdId) + query, user);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return read(response, new TypeReference<PageResponse<SettlementResponse>>() { });
    }

    /** Balance de cada persona listada, como texto con escala 2 ("-4.99"). */
    protected static Map<UUID, String> byUser(BalanceResponse balances) {
        return balances.members().stream()
                .collect(Collectors.toMap(MemberBalance::userId, m -> m.balance().toPlainString()));
    }

    /** Aplica cada pago sugerido como liquidación, registrada por quien paga. */
    protected void applySuggestions(UUID householdId, List<SuggestedTransfer> transfers, Map<UUID, TestUser> users) {
        for (SuggestedTransfer t : transfers) {
            settle(users.get(t.fromUserId()), householdId, t.fromUserId(), t.toUserId(), t.amount().toPlainString());
        }
    }

    protected void leave(TestUser user, UUID householdId) {
        ResponseEntity<String> response = call(HttpMethod.POST, householdPath(householdId) + "/leave", user, null);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    protected void expel(TestUser owner, UUID householdId, TestUser member) {
        ResponseEntity<String> response = call(HttpMethod.DELETE,
                householdPath(householdId) + "/members/" + member.id(), owner, null);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    protected static String settlementsPath(UUID householdId) {
        return householdPath(householdId) + "/settlements";
    }

    protected static String settlementPath(UUID householdId, UUID settlementId) {
        return settlementsPath(householdId) + "/" + settlementId;
    }

    // --- Comprobaciones directas en BD ---

    /**
     * Balances calculados en SQL, sin pasar por JPA ni por {@code BalanceCalculator}: pagado − partes
     * + enviado − recibido. Solo usuarios con algún movimiento.
     */
    protected Map<UUID, String> dbBalances(UUID householdId) {
        return jdbc.queryForList("""
                select u, sum(v) as balance from (
                    select paid_by as u, amount as v from shared_expenses where household_id = ?
                    union all
                    select s.user_id, -s.amount from expense_shares s
                        join shared_expenses e on e.id = s.expense_id where e.household_id = ?
                    union all
                    select from_user_id, amount from settlements where household_id = ?
                    union all
                    select to_user_id, -amount from settlements where household_id = ?
                ) t group by u
                """, householdId, householdId, householdId, householdId).stream()
                .collect(Collectors.toMap(r -> (UUID) r.get("u"),
                        r -> ((BigDecimal) r.get("balance")).toPlainString()));
    }

    /** Σ balances == 0 en BD y los balances de la API coinciden con los de SQL. */
    protected void assertMatchesDb(UUID householdId, BalanceResponse balances) {
        Map<UUID, String> db = dbBalances(householdId);
        assertThat(db.values().stream().map(BigDecimal::new).reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("Σ balances en BD").isEqualByComparingTo("0");
        Map<UUID, String> api = byUser(balances);
        db.forEach((user, balance) -> assertThat(api).containsEntry(user, balance));
        api.forEach((user, balance) -> {
            if (!db.containsKey(user)) {
                assertThat(balance).isEqualTo("0.00");
            }
        });
    }
}
