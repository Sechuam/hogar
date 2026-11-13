package com.hogar.household;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.dto.HouseholdResponse;
import com.hogar.household.dto.InvitationResponse;
import com.hogar.household.dto.MemberResponse;
import com.hogar.support.TestUsers.TestUser;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Matriz de control de acceso de todos los endpoints bajo {@code /api/households/{id}/**}.
 *
 * <p>Al añadir un endpoint nuevo bajo un hogar basta con añadirlo a {@link #ENDPOINTS}: queda
 * cubierto por el aislamiento (404 a no miembros, ex-miembros y expulsados), por el 403 de rol
 * si es de OWNER y por el 400 ante IDs mal formados.
 */
class HouseholdAccessControlIT extends HouseholdApiSupport {

    /**
     * Endpoint con su plantilla de ruta: {@code {h}} = hogar, {@code {u}} = usuario objetivo
     * (siempre el OWNER, el objetivo más sensible), {@code {i}} = invitación existente.
     */
    record Endpoint(HttpMethod method, String template, Object body, boolean ownerOnly) {

        String path(String householdId, String userId, String invitationId) {
            return template.replace("{h}", householdId).replace("{u}", userId).replace("{i}", invitationId);
        }

        @Override
        public String toString() {
            return method + " " + template;
        }
    }

    /**
     * Ingresos y gastos: el guard de membresía va antes de buscar el recurso, así que basta un ID
     * cualquiera (el aislamiento de recursos concretos se prueba en {@code IncomeIT}/{@code ExpenseIT}).
     * Los cuerpos son válidos para que la validación (400) no tape el 404.
     */
    static final UUID ANY_ID = UUID.randomUUID();
    static final Map<String, Object> INCOME_BODY = Map.of("amount", "10.00", "concept", "x", "date", "2026-03-01");
    static final Map<String, Object> EXPENSE_BODY =
            Map.of("amount", "10.00", "description", "x", "category", "RENT", "date", "2026-03-01");

    static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint(HttpMethod.GET, "/api/households/{h}", null, false),
            new Endpoint(HttpMethod.PATCH, "/api/households/{h}", Map.of("name", "Hackeado"), true),
            new Endpoint(HttpMethod.GET, "/api/households/{h}/members", null, false),
            new Endpoint(HttpMethod.PATCH, "/api/households/{h}/members/{u}", Map.of("role", "MEMBER"), true),
            new Endpoint(HttpMethod.DELETE, "/api/households/{h}/members/{u}", null, true),
            new Endpoint(HttpMethod.POST, "/api/households/{h}/leave", null, false),
            new Endpoint(HttpMethod.POST, "/api/households/{h}/invitations", Map.of("maxUses", 1), true),
            new Endpoint(HttpMethod.GET, "/api/households/{h}/invitations", null, true),
            new Endpoint(HttpMethod.DELETE, "/api/households/{h}/invitations/{i}", null, true),
            new Endpoint(HttpMethod.POST, "/api/households/{h}/incomes", INCOME_BODY, false),
            new Endpoint(HttpMethod.GET, "/api/households/{h}/incomes", null, false),
            new Endpoint(HttpMethod.GET, "/api/households/{h}/incomes/summary?month=2026-03", null, false),
            new Endpoint(HttpMethod.GET, "/api/households/{h}/incomes/" + ANY_ID, null, false),
            new Endpoint(HttpMethod.PUT, "/api/households/{h}/incomes/" + ANY_ID, INCOME_BODY, false),
            new Endpoint(HttpMethod.DELETE, "/api/households/{h}/incomes/" + ANY_ID, null, false),
            new Endpoint(HttpMethod.POST, "/api/households/{h}/expenses", EXPENSE_BODY, false),
            new Endpoint(HttpMethod.GET, "/api/households/{h}/expenses", null, false),
            new Endpoint(HttpMethod.GET, "/api/households/{h}/expenses/" + ANY_ID, null, false),
            new Endpoint(HttpMethod.PUT, "/api/households/{h}/expenses/" + ANY_ID, EXPENSE_BODY, false),
            new Endpoint(HttpMethod.DELETE, "/api/households/{h}/expenses/" + ANY_ID, null, false));

    static Stream<Endpoint> allEndpoints() {
        return ENDPOINTS.stream();
    }

    static Stream<Endpoint> ownerEndpoints() {
        return ENDPOINTS.stream().filter(Endpoint::ownerOnly);
    }

    /** Cada endpoint con cada posición de ID que contiene. */
    static Stream<Arguments> malformedIdPositions() {
        return ENDPOINTS.stream().flatMap(e -> Arrays.stream(new String[] {"{h}", "{u}", "{i}"})
                .filter(e.template()::contains)
                .map(placeholder -> Arguments.of(e, placeholder)));
    }

    /** Hogar de Ana con una invitación viva, un ex-miembro (salió), un expulsado y un extraño. */
    private record Scenario(TestUser owner, UUID householdId, InvitationResponse invitation,
                            TestUser stranger, TestUser formerMember, TestUser expelled, int invitationCount) {
    }

    private Scenario scenario() {
        TestUser ana = users().create("Ana");
        TestUser eva = users().create("Eva");
        TestUser luis = users().create("Luis");
        TestUser marta = users().create("Marta");
        UUID id = createHousehold(ana, "Piso de Ana").id();
        InvitationResponse invitation = invite(ana, id, null, null);

        join(ana, id, luis);
        assertThat(call(HttpMethod.POST, householdPath(id) + "/leave", luis, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        join(ana, id, marta);
        assertThat(call(HttpMethod.DELETE, memberPath(id, marta.id()), ana, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        int invitationCount = read(get(invitationsPath(id), ana), InvitationResponse[].class).length;
        return new Scenario(ana, id, invitation, eva, luis, marta, invitationCount);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEndpoints")
    void outsidersGetHouseholdNotFoundAndChangeNothing(Endpoint endpoint) {
        Scenario s = scenario();
        String path = endpoint.path(s.householdId().toString(), s.owner().id().toString(),
                s.invitation().id().toString());

        for (TestUser outsider : List.of(s.stranger(), s.formerMember(), s.expelled())) {
            ResponseEntity<String> response = call(endpoint.method(), path, outsider, endpoint.body());
            assertProblem(response, HttpStatus.NOT_FOUND, "household-not-found");
        }
        assertUntouched(s);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEndpoints")
    void unknownHouseholdIsIndistinguishableFromAForeignOne(Endpoint endpoint) {
        Scenario s = scenario();
        String path = endpoint.path(UUID.randomUUID().toString(), s.owner().id().toString(),
                s.invitation().id().toString());

        assertProblem(call(endpoint.method(), path, s.owner(), endpoint.body()), HttpStatus.NOT_FOUND,
                "household-not-found");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ownerEndpoints")
    void memberGetsOwnerRoleRequiredAndChangesNothing(Endpoint endpoint) {
        Scenario s = scenario();
        TestUser pablo = users().create("Pablo");
        join(s.owner(), s.householdId(), pablo);
        String path = endpoint.path(s.householdId().toString(), s.owner().id().toString(),
                s.invitation().id().toString());

        assertProblem(call(endpoint.method(), path, pablo, endpoint.body()), HttpStatus.FORBIDDEN,
                "owner-role-required");

        HouseholdResponse household = read(get(householdPath(s.householdId()), s.owner()), HouseholdResponse.class);
        assertThat(household.name()).isEqualTo("Piso de Ana");
        assertThat(household.myRole()).isEqualTo(HouseholdRole.OWNER);
        assertThat(household.memberCount()).isEqualTo(2);
        // join() de Pablo creó una invitación más; ninguna revocada ni creada por el MEMBER.
        assertThat(invitations(s)).hasSize(s.invitationCount() + 1)
                .extracting(InvitationResponse::revoked).containsOnly(false);
    }

    @ParameterizedTest(name = "{0} con {1} mal formado")
    @MethodSource("malformedIdPositions")
    void malformedIdIsBadRequestProblemNotServerError(Endpoint endpoint, String placeholder) {
        Scenario s = scenario();
        String householdId = "{h}".equals(placeholder) ? "no-es-uuid" : s.householdId().toString();
        String userId = "{u}".equals(placeholder) ? "no-es-uuid" : s.owner().id().toString();
        String invitationId = "{i}".equals(placeholder) ? "no-es-uuid" : s.invitation().id().toString();
        String path = endpoint.path(householdId, userId, invitationId);

        // Igual para el dueño que para un extraño: un ID mal formado no depende del hogar.
        for (TestUser user : List.of(s.owner(), s.stranger())) {
            ResponseEntity<String> response = call(endpoint.method(), path, user, endpoint.body());
            assertProblem(response, HttpStatus.BAD_REQUEST, "bad-request");
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        }
        assertUntouched(s);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allEndpoints")
    void anonymousGetsUnauthorized(Endpoint endpoint) {
        String path = endpoint.path(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString());

        assertProblem(call(endpoint.method(), path, null, endpoint.body()), HttpStatus.UNAUTHORIZED, "unauthorized");
    }

    private void assertUntouched(Scenario s) {
        HouseholdResponse household = read(get(householdPath(s.householdId()), s.owner()), HouseholdResponse.class);
        assertThat(household.name()).isEqualTo("Piso de Ana");
        assertThat(household.myRole()).isEqualTo(HouseholdRole.OWNER);
        assertThat(household.memberCount()).isEqualTo(1);
        assertThat(read(get(membersPath(s.householdId()), s.owner()), MemberResponse[].class))
                .extracting(MemberResponse::userId).containsExactly(s.owner().id());
        assertThat(invitations(s)).hasSize(s.invitationCount())
                .extracting(InvitationResponse::revoked).containsOnly(false);
    }

    private List<InvitationResponse> invitations(Scenario s) {
        return List.of(read(get(invitationsPath(s.householdId()), s.owner()), InvitationResponse[].class));
    }
}
