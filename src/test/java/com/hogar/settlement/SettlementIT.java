package com.hogar.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.household.domain.SplitPolicy;
import com.hogar.settlement.dto.SettlementResponse;
import com.hogar.shared.web.PageResponse;
import com.hogar.support.TestUsers.TestUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Liquidaciones de punta a punta: alta, consulta, permisos, aislamiento entre hogares y validaciones. */
class SettlementIT extends SettlementApiSupport {

    /** Ana (OWNER), Bob y Carla (MEMBER). */
    private record Home(UUID id, TestUser ana, TestUser bob, TestUser carla) {
    }

    private Home home() {
        TestUser ana = users().create("Ana");
        TestUser bob = users().create("Bob");
        TestUser carla = users().create("Carla");
        UUID h = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, h, bob, carla);
        return new Home(h, ana, bob, carla);
    }

    private SettlementResponse getSettlement(TestUser user, UUID h, UUID id) {
        ResponseEntity<String> response = get(settlementPath(h, id), user);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return read(response, SettlementResponse.class);
    }

    private long dbCount(UUID settlementId) {
        return jdbc.queryForObject("select count(*) from settlements where id = ?", Long.class, settlementId);
    }

    // --- Alta y consulta ---

    @Test
    void payerRegistersAndEveryMemberCanReadIt() {
        Home s = home();

        SettlementResponse created = settle(s.bob(), s.id(),
                settlementBody(s.bob().id(), s.ana().id(), new BigDecimal("12.5"), MARCH_5, "  Bizum de marzo "));

        assertThat(created.fromUserId()).isEqualTo(s.bob().id());
        assertThat(created.toUserId()).isEqualTo(s.ana().id());
        assertThat(created.amount().toPlainString()).isEqualTo("12.50");
        assertThat(created.currency()).isEqualTo("EUR");
        assertThat(created.date()).isEqualTo(MARCH_5);
        assertThat(created.note()).isEqualTo("Bizum de marzo");
        assertThat(created.createdBy()).isEqualTo(s.bob().id());
        assertThat(created.createdAt()).isNotNull();
        assertThat(getSettlement(s.carla(), s.id(), created.id())).usingRecursiveComparison()
                .ignoringFields("createdAt").isEqualTo(created);
        // Escala 2 también en el JSON (no basta con el BigDecimal ya deserializado).
        assertThat(get(settlementPath(s.id(), created.id()), s.ana()).getBody()).contains("\"amount\":12.50");
    }

    @Test
    void receiverAndOwnerCanRegisterButAnUnrelatedMemberCannot() {
        Home s = home();

        assertThat(settle(s.ana(), s.id(), s.bob().id(), s.ana().id(), "1.00").createdBy()).isEqualTo(s.ana().id());
        SettlementResponse byOwner = settle(s.ana(), s.id(), s.bob().id(), s.carla().id(), "2.00");
        assertThat(byOwner.createdBy()).isEqualTo(s.ana().id());

        assertProblem(settleCall(s.carla(), s.id(), s.bob().id(), s.ana().id(), "3.00"), HttpStatus.FORBIDDEN,
                "settlement-forbidden");
        assertThat(listSettlements(s.ana(), s.id(), "").totalElements()).isEqualTo(2);
    }

    // --- Borrado ---

    @Test
    void unrelatedMemberCannotDeleteButTheReceiverCan() {
        Home s = home();
        SettlementResponse created = settle(s.bob(), s.id(), s.bob().id(), s.ana().id(), "10.00");

        assertProblem(deleteSettlement(s.carla(), s.id(), created.id()), HttpStatus.FORBIDDEN,
                "settlement-delete-forbidden");
        assertThat(dbCount(created.id())).isEqualTo(1);

        // Ana (receptora) anula un pago que no recibió.
        assertThat(deleteSettlement(s.ana(), s.id(), created.id()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbCount(created.id())).isZero();
        assertProblem(get(settlementPath(s.id(), created.id()), s.bob()), HttpStatus.NOT_FOUND,
                "settlement-not-found");
        assertProblem(deleteSettlement(s.bob(), s.id(), created.id()), HttpStatus.NOT_FOUND, "settlement-not-found");
    }

    @Test
    void creatorPayerAndOwnerCanDelete() {
        Home s = home();
        // Ana (OWNER) registra un pago entre Bob y Carla y lo borra ella misma.
        SettlementResponse byOwner = settle(s.ana(), s.id(), s.bob().id(), s.carla().id(), "1.00");
        assertThat(deleteSettlement(s.ana(), s.id(), byOwner.id()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // El pagador borra el suyo.
        SettlementResponse byPayer = settle(s.carla(), s.id(), s.bob().id(), s.carla().id(), "1.00");
        assertThat(deleteSettlement(s.bob(), s.id(), byPayer.id()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // La OWNER borra uno en el que no es parte ni creadora.
        SettlementResponse other = settle(s.bob(), s.id(), s.bob().id(), s.carla().id(), "1.00");
        assertThat(deleteSettlement(s.ana(), s.id(), other.id()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(listSettlements(s.ana(), s.id(), "").totalElements()).isZero();
    }

    // --- Aislamiento entre hogares ---

    @Test
    void settlementOfAnotherHouseholdIsNotFoundAndUntouched() {
        Home s = home();
        TestUser dani = users().create("Dani");
        TestUser eva = users().create("Eva");
        UUID other = createHousehold(dani, SplitPolicy.EQUAL);
        join(dani, other, eva);
        SettlementResponse foreign = settle(dani, other, eva.id(), dani.id(), "5.00");

        assertProblem(get(settlementPath(s.id(), foreign.id()), s.ana()), HttpStatus.NOT_FOUND,
                "settlement-not-found");
        assertProblem(deleteSettlement(s.ana(), s.id(), foreign.id()), HttpStatus.NOT_FOUND, "settlement-not-found");
        assertThat(dbCount(foreign.id())).isEqualTo(1);
        assertThat(listSettlements(s.ana(), s.id(), "").content()).isEmpty();
        // Un extraño no ve el hogar ajeno.
        assertProblem(get(settlementPath(other, foreign.id()), s.ana()), HttpStatus.NOT_FOUND, "household-not-found");
    }

    @Test
    void partiesMustBeCurrentOrFormerMembersOfThisHousehold() {
        Home s = home();
        TestUser dani = users().create("Dani");
        createHousehold(dani, SplitPolicy.EQUAL);

        assertProblem(settleCall(s.ana(), s.id(), s.ana().id(), dani.id(), "1.00"),
                HttpStatus.UNPROCESSABLE_ENTITY, "settlement-party-not-member");
        assertProblem(settleCall(s.ana(), s.id(), dani.id(), s.ana().id(), "1.00"),
                HttpStatus.UNPROCESSABLE_ENTITY, "settlement-party-not-member");
        assertProblem(settleCall(s.bob(), s.id(), s.bob().id(), UUID.randomUUID(), "1.00"),
                HttpStatus.UNPROCESSABLE_ENTITY, "settlement-party-not-member");
        assertThat(listSettlements(s.ana(), s.id(), "").totalElements()).isZero();
    }

    // --- Validaciones ---

    @Test
    void sameParticipantTwiceIsBadRequest() {
        Home s = home();
        assertValidationError(settleCall(s.bob(), s.id(), s.bob().id(), s.bob().id(), "1.00"), "distinctParties");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1.00", "1.005", "100000000000000000.00"})
    void invalidAmountIsBadRequest(String amount) {
        Home s = home();
        ResponseEntity<String> response = call(HttpMethod.POST, settlementsPath(s.id()), s.bob(),
                settlementBody(s.bob().id(), s.ana().id(), new BigDecimal(amount), MARCH_5, null));
        assertValidationError(response, "amount");
    }

    @ParameterizedTest
    @ValueSource(strings = {"10000-01-01", "0000-12-31", "2026-02-30", "no-es-fecha"})
    void invalidDateIsBadRequest(String date) {
        Home s = home();
        Map<String, Object> body = settlementBody(s.bob().id(), s.ana().id(), "1.00", null, null);
        body.put("date", date);
        ResponseEntity<String> response = call(HttpMethod.POST, settlementsPath(s.id()), s.bob(), body);
        assertValidationError(response, "date");
    }

    @Test
    void extremeValidDatesAndAmountsAreAccepted() {
        Home s = home();
        assertThat(settle(s.bob(), s.id(), settlementBody(s.bob().id(), s.ana().id(), new BigDecimal("0.01"),
                LocalDate.of(1, 1, 1), null)).date()).isEqualTo(LocalDate.of(1, 1, 1));
        assertThat(settle(s.bob(), s.id(), settlementBody(s.bob().id(), s.ana().id(),
                new BigDecimal("999999999.99"), LocalDate.of(9999, 12, 31), null)).amount().toPlainString())
                .isEqualTo("999999999.99");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", " 　", "\t\n"})
    void blankNoteIsBadRequest(String note) {
        Home s = home();
        ResponseEntity<String> response = call(HttpMethod.POST, settlementsPath(s.id()), s.bob(),
                settlementBody(s.bob().id(), s.ana().id(), "1.00", MARCH_5, note));
        assertValidationError(response, "note");
    }

    @Test
    void noteLongerThan200IsBadRequestAnd200IsAccepted() {
        Home s = home();
        assertValidationError(call(HttpMethod.POST, settlementsPath(s.id()), s.bob(),
                settlementBody(s.bob().id(), s.ana().id(), "1.00", MARCH_5, "x".repeat(201))), "note");
        assertThat(settle(s.bob(), s.id(), settlementBody(s.bob().id(), s.ana().id(), "1.00", MARCH_5,
                "x".repeat(200))).note()).hasSize(200);
    }

    @Test
    void missingFieldsAreBadRequest() {
        Home s = home();
        ResponseEntity<String> response = call(HttpMethod.POST, settlementsPath(s.id()), s.bob(), Map.of());
        assertProblem(response, HttpStatus.BAD_REQUEST, "validation-error");
        assertThat(tree(response).path("errors").findValuesAsText("field"))
                .contains("fromUserId", "toUserId", "amount", "date");
    }

    // --- Listado ---

    @Test
    void listIsOrderedByDateThenCreationAndFiltersByDateAndParticipant() {
        Home s = home();
        SettlementResponse jan = settle(s.bob(), s.id(), settlementBody(s.bob().id(), s.ana().id(), "1.00",
                LocalDate.of(2026, 1, 10), null));
        SettlementResponse feb1 = settle(s.carla(), s.id(), settlementBody(s.carla().id(), s.ana().id(), "2.00",
                LocalDate.of(2026, 2, 10), null));
        SettlementResponse feb2 = settle(s.bob(), s.id(), settlementBody(s.bob().id(), s.carla().id(), "3.00",
                LocalDate.of(2026, 2, 10), null));
        SettlementResponse mar = settle(s.ana(), s.id(), settlementBody(s.ana().id(), s.carla().id(), "4.00",
                LocalDate.of(2026, 3, 1), null));

        assertThat(listSettlements(s.bob(), s.id(), "").content()).extracting(SettlementResponse::id)
                .containsExactly(mar.id(), feb2.id(), feb1.id(), jan.id());
        // Fechas inclusivas.
        assertThat(listSettlements(s.bob(), s.id(), "?from=2026-02-10&to=2026-03-01").content())
                .extracting(SettlementResponse::id).containsExactly(mar.id(), feb2.id(), feb1.id());
        assertThat(listSettlements(s.bob(), s.id(), "?to=2026-01-10").content())
                .extracting(SettlementResponse::id).containsExactly(jan.id());
        assertThat(listSettlements(s.bob(), s.id(), "?from=2026-02-11").content())
                .extracting(SettlementResponse::id).containsExactly(mar.id());
        // userId: como pagador o como receptor.
        assertThat(listSettlements(s.bob(), s.id(), "?userId=" + s.carla().id()).content())
                .extracting(SettlementResponse::id).containsExactly(mar.id(), feb2.id(), feb1.id());
        assertThat(listSettlements(s.bob(), s.id(), "?userId=" + s.bob().id() + "&from=2026-02-01").content())
                .extracting(SettlementResponse::id).containsExactly(feb2.id());
        assertThat(listSettlements(s.bob(), s.id(), "?userId=" + UUID.randomUUID()).content()).isEmpty();
    }

    @Test
    void listIsPaginated() {
        Home s = home();
        for (int day = 1; day <= 5; day++) {
            settle(s.bob(), s.id(), settlementBody(s.bob().id(), s.ana().id(), "1.00", LocalDate.of(2026, 4, day),
                    null));
        }

        PageResponse<SettlementResponse> first = listSettlements(s.ana(), s.id(), "?page=0&size=2");
        PageResponse<SettlementResponse> last = listSettlements(s.ana(), s.id(), "?page=2&size=2");

        assertThat(first.content()).extracting(SettlementResponse::date)
                .containsExactly(LocalDate.of(2026, 4, 5), LocalDate.of(2026, 4, 4));
        assertThat(first.totalElements()).isEqualTo(5);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(last.content()).extracting(SettlementResponse::date).containsExactly(LocalDate.of(2026, 4, 1));
        assertThat(listSettlements(s.ana(), s.id(), "?page=" + PageResponse.MAX_PAGE + "&size=100").content())
                .isEmpty();
    }

    @Test
    void invalidListParametersAreBadRequest() {
        Home s = home();
        assertProblem(get(settlementsPath(s.id()) + "?from=2026-03-02&to=2026-03-01", s.ana()),
                HttpStatus.BAD_REQUEST, "invalid-date-range");
        for (String query : new String[] {"?size=0", "?size=101", "?page=-1", "?page=" + (PageResponse.MAX_PAGE + 1),
                "?userId=no-es-uuid", "?from=2026-13-01"}) {
            ResponseEntity<String> response = get(settlementsPath(s.id()) + query, s.ana());
            assertThat(response.getStatusCode()).as(query + " → " + response.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
