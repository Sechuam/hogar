package com.hogar.household;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.dto.HouseholdResponse;
import com.hogar.household.dto.InvitationResponse;
import com.hogar.household.dto.MemberResponse;
import com.hogar.support.TestUsers.TestUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Casos límite de hogares: cuerpos mal formados, expulsados y carreras entre peticiones. */
class HouseholdEdgeCasesIT extends HouseholdApiSupport {

    // ---------------------------------------------------------------- cuerpos con tipos incorrectos

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"splitPolicy\":\"FOO\"}",
            "{\"splitPolicy\":\"equal\"}",
            "{\"name\":{\"a\":1}}",
            "{\"name\":[\"Piso\"]}",
            "[]",
            "\"Piso\"",
            "{\"name\":",
            ""})
    void patchHouseholdWithMalformedBodyIsBadRequest(String body) {
        TestUser ana = users().create("Ana");
        UUID id = createHousehold(ana, "Piso").id();

        ResponseEntity<String> response = raw(HttpMethod.PATCH, householdPath(id), ana, body);

        assertProblem(response, HttpStatus.BAD_REQUEST, "bad-request");
        assertThat(read(get(householdPath(id), ana), HouseholdResponse.class).name()).isEqualTo("Piso");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"Piso\",\"splitPolicy\":\"FOO\"}",
            "{\"name\":\"Piso\",\"currency\":[\"EUR\"]}",
            "{\"name\":\"Piso\",\"splitPolicy\":{}}",
            "{nombre:'Piso'}"})
    void createHouseholdWithMalformedBodyIsBadRequest(String body) {
        assertProblem(raw(HttpMethod.POST, "/api/households", users().create("Ana"), body),
                HttpStatus.BAD_REQUEST, "bad-request");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"role\":\"ADMIN\"}",
            "{\"role\":\"owner\"}",
            "{\"role\":true}",
            "{\"role\":[\"OWNER\"]}"})
    void changeRoleWithMalformedBodyIsBadRequest(String body) {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        join(ana, id, luis);

        assertProblem(raw(HttpMethod.PATCH, memberPath(id, luis.id()), ana, body), HttpStatus.BAD_REQUEST,
                "bad-request");
        assertThat(members(ana, id)).extracting(MemberResponse::role)
                .containsExactly(HouseholdRole.OWNER, HouseholdRole.MEMBER);
    }

    @Disabled("BUG: Jackson acepta enums por ordinal: {\"role\":0} promociona a OWNER y {\"splitPolicy\":1} "
            + "pone PROPORTIONAL. Fix: spring.jackson.deserialization.fail-on-numbers-for-enums=true en application.yml")
    @Test
    void numericEnumOrdinalsAreRejected() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        join(ana, id, luis);

        assertProblem(raw(HttpMethod.PATCH, memberPath(id, luis.id()), ana, "{\"role\":0}"),
                HttpStatus.BAD_REQUEST, "bad-request");
        assertProblem(raw(HttpMethod.PATCH, householdPath(id), ana, "{\"splitPolicy\":1}"),
                HttpStatus.BAD_REQUEST, "bad-request");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"ttlHours\":\"mucho\"}",
            "{\"maxUses\":{}}",
            "{\"ttlHours\":99999999999999999999}",
            "[1,2]"})
    void createInvitationWithMalformedBodyIsBadRequest(String body) {
        TestUser ana = users().create("Ana");
        UUID id = createHousehold(ana, "Piso").id();

        assertProblem(raw(HttpMethod.POST, invitationsPath(id), ana, body), HttpStatus.BAD_REQUEST, "bad-request");
        assertThat(read(get(invitationsPath(id), ana), InvitationResponse[].class)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"maxUses\":0}", "{\"maxUses\":51}", "{\"ttlHours\":0}", "{\"ttlHours\":-5}"})
    void createInvitationOutOfRangeIsValidationError(String body) {
        TestUser ana = users().create("Ana");
        UUID id = createHousehold(ana, "Piso").id();

        assertProblem(raw(HttpMethod.POST, invitationsPath(id), ana, body), HttpStatus.BAD_REQUEST,
                "validation-error");
    }

    @Test
    void unsupportedContentTypeIsProblemDetail() {
        TestUser ana = users().create("Ana");
        HttpHeaders headers = ana.bearer();
        headers.setContentType(MediaType.TEXT_PLAIN);

        ResponseEntity<String> response = rest.exchange("/api/households", HttpMethod.POST,
                new HttpEntity<>("Piso", headers), String.class);

        assertProblem(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type");
    }

    @Test
    void unsupportedMethodIsProblemDetail() {
        TestUser ana = users().create("Ana");
        UUID id = createHousehold(ana, "Piso").id();

        assertProblem(call(HttpMethod.DELETE, householdPath(id), ana, null), HttpStatus.METHOD_NOT_ALLOWED,
                "method-not-allowed");
    }

    @Test
    void overlongInvitationCodeIsNotFound() {
        assertProblem(accept(users().create("Eva"), "A".repeat(200)), HttpStatus.NOT_FOUND, "invitation-not-found");
        assertProblem(accept(users().create("Eva"), "----"), HttpStatus.NOT_FOUND, "invitation-not-found");
    }

    // ---------------------------------------------------------------- expulsados y ex-miembros

    @Test
    void expelledUserKeepsAValidTokenButNoLongerSeesTheHousehold() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        UUID other = createHousehold(luis, "Mi estudio").id();
        join(ana, id, luis);

        assertThat(call(HttpMethod.DELETE, memberPath(id, luis.id()), ana, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // El access token no se revoca (stateless), pero la autorización es por hogar y en cada petición.
        assertThat(get("/api/users/me", luis).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(get("/api/households", luis), HouseholdResponse[].class))
                .extracting(HouseholdResponse::id).containsExactly(other);
        assertProblem(get(householdPath(id), luis), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(get(membersPath(id), luis), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(call(HttpMethod.POST, householdPath(id) + "/leave", luis, null), HttpStatus.NOT_FOUND,
                "household-not-found");
        assertThat(members(ana, id)).extracting(MemberResponse::userId).containsExactly(ana.id());
        // Su otro hogar sigue intacto.
        assertThat(get(householdPath(other), luis).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void formerMembersCannotBeTargetedAgain() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        join(ana, id, luis);
        call(HttpMethod.DELETE, memberPath(id, luis.id()), ana, null);

        assertProblem(call(HttpMethod.DELETE, memberPath(id, luis.id()), ana, null), HttpStatus.NOT_FOUND,
                "member-not-found");
        assertProblem(call(HttpMethod.PATCH, memberPath(id, luis.id()), ana, Map.of("role", "OWNER")),
                HttpStatus.NOT_FOUND, "member-not-found");
        assertProblem(call(HttpMethod.PATCH, memberPath(id, UUID.randomUUID()), ana, Map.of("role", "OWNER")),
                HttpStatus.NOT_FOUND, "member-not-found");
    }

    @Disabled("BUG (diseño, media): un expulsado vuelve a entrar con la misma invitación ilimitada que ya usó "
            + "(vive 7 días por defecto). Fix: al expulsar, rechazar en InvitationService.accept las invitaciones "
            + "creadas antes de la expulsión para ese usuario (requiere distinguir expulsión de abandono en "
            + "memberships, p. ej. columna removed_by), o revocar las invitaciones vivas al expulsar")
    @Test
    void expelledUserCannotRejoinWithTheInvitationHeAlreadyUsed() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        InvitationResponse invitation = invite(ana, id, null, null);
        assertThat(accept(luis, invitation.code()).getStatusCode()).isEqualTo(HttpStatus.OK);
        call(HttpMethod.DELETE, memberPath(id, luis.id()), ana, null);

        assertThat(accept(luis, invitation.code()).getStatusCode()).isNotEqualTo(HttpStatus.OK);
        assertThat(members(ana, id)).extracting(MemberResponse::userId).containsExactly(ana.id());
    }

    // ---------------------------------------------------------------- concurrencia

    @RepeatedTest(3)
    void singleUseInvitationAcceptedConcurrentlyAdmitsExactlyOne() throws Exception {
        TestUser ana = users().create("Ana");
        UUID id = createHousehold(ana, "Piso").id();
        String code = invite(ana, id, null, 1).code();
        List<TestUser> guests = List.of(users().create("Eva"), users().create("Luis"), users().create("Marta"),
                users().create("Pablo"));

        List<ResponseEntity<String>> responses = concurrently(guests.stream()
                .<Callable<ResponseEntity<String>>>map(g -> () -> accept(g, code)).toList());

        assertThat(responses).extracting(ResponseEntity::getStatusCode).containsOnlyOnce(HttpStatus.OK);
        responses.stream().filter(r -> r.getStatusCode() != HttpStatus.OK)
                .forEach(r -> assertProblem(r, HttpStatus.UNPROCESSABLE_ENTITY, "invitation-exhausted"));
        assertThat(members(ana, id)).hasSize(2);
        assertThat(read(get(invitationsPath(id), ana), InvitationResponse[].class)[0].uses()).isEqualTo(1);
    }

    @RepeatedTest(3)
    void sameUserAcceptingTwoInvitationsAtOnceJoinsOnceWithoutServerError() throws Exception {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        String first = invite(ana, id, null, null).code();
        String second = invite(ana, id, null, null).code();

        List<ResponseEntity<String>> responses = concurrently(List.of(
                () -> accept(luis, first), () -> accept(luis, second), () -> accept(luis, first)));

        assertThat(responses).extracting(ResponseEntity::getStatusCode).containsOnlyOnce(HttpStatus.OK);
        responses.stream().filter(r -> r.getStatusCode() != HttpStatus.OK)
                .forEach(r -> assertProblem(r, HttpStatus.CONFLICT, "already-member"));
        assertThat(members(ana, id)).extracting(MemberResponse::userId).containsExactly(ana.id(), luis.id());
    }

    @RepeatedTest(5)
    void soleOwnerLeavingWhileSomeoneJoinsNeverLeavesMembersWithoutOwner() throws Exception {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        String code = invite(ana, id, null, null).code();

        List<ResponseEntity<String>> responses = concurrently(List.of(
                () -> call(HttpMethod.POST, householdPath(id) + "/leave", ana, null),
                () -> accept(luis, code)));
        HttpStatusCode leave = responses.get(0).getStatusCode();
        HttpStatusCode join = responses.get(1).getStatusCode();

        if (leave == HttpStatus.NO_CONTENT) {
            // Ana salió primero: el hogar quedó vacío y la invitación ya no vale.
            assertProblem(responses.get(1), HttpStatus.NOT_FOUND, "invitation-not-found");
            assertProblem(get(householdPath(id), luis), HttpStatus.NOT_FOUND, "household-not-found");
        } else {
            // Luis entró primero: Ana es la última OWNER con otro miembro y no puede irse.
            assertThat(join).isEqualTo(HttpStatus.OK);
            assertProblem(responses.get(0), HttpStatus.UNPROCESSABLE_ENTITY, "last-owner");
            assertThat(members(ana, id)).extracting(MemberResponse::role)
                    .containsExactly(HouseholdRole.OWNER, HouseholdRole.MEMBER);
        }
    }

    @RepeatedTest(5)
    void twoOwnersExpellingEachOtherAtOnceLeaveExactlyOne() throws Exception {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        join(ana, id, luis);
        call(HttpMethod.PATCH, memberPath(id, luis.id()), ana, Map.of("role", "OWNER"));

        List<ResponseEntity<String>> responses = concurrently(List.of(
                () -> call(HttpMethod.DELETE, memberPath(id, luis.id()), ana, null),
                () -> call(HttpMethod.DELETE, memberPath(id, ana.id()), luis, null)));

        assertThat(responses).extracting(ResponseEntity::getStatusCode).containsOnlyOnce(HttpStatus.NO_CONTENT);
        responses.stream().filter(r -> r.getStatusCode() != HttpStatus.NO_CONTENT)
                .forEach(r -> assertProblem(r, HttpStatus.NOT_FOUND, "household-not-found"));
        TestUser survivor = responses.get(0).getStatusCode() == HttpStatus.NO_CONTENT ? ana : luis;
        assertThat(members(survivor, id)).extracting(MemberResponse::role).containsExactly(HouseholdRole.OWNER);
    }

    @RepeatedTest(3)
    void expelAndLeaveAtOnceNeverFailWithServerError() throws Exception {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        join(ana, id, luis);

        List<ResponseEntity<String>> responses = concurrently(List.of(
                () -> call(HttpMethod.DELETE, memberPath(id, luis.id()), ana, null),
                () -> call(HttpMethod.POST, householdPath(id) + "/leave", luis, null)));

        assertThat(responses).extracting(ResponseEntity::getStatusCode).containsOnlyOnce(HttpStatus.NO_CONTENT);
        ResponseEntity<String> loser = responses.stream()
                .filter(r -> r.getStatusCode() != HttpStatus.NO_CONTENT).findFirst().orElseThrow();
        assertThat(loser.getStatusCode()).as(loser.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(members(ana, id)).extracting(MemberResponse::userId).containsExactly(ana.id());
    }

    @RepeatedTest(3)
    void concurrentPatchesEitherSucceedOrConflictButNeverFail() throws Exception {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        join(ana, id, luis);
        call(HttpMethod.PATCH, memberPath(id, luis.id()), ana, Map.of("role", "OWNER"));

        List<Callable<ResponseEntity<String>>> patches = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            TestUser who = i % 2 == 0 ? ana : luis;
            String name = "Piso " + i;
            patches.add(() -> call(HttpMethod.PATCH, householdPath(id), who,
                    Map.of("name", name, "splitPolicy", "PROPORTIONAL")));
        }
        List<ResponseEntity<String>> responses = concurrently(patches);

        assertThat(responses).extracting(ResponseEntity::getStatusCode).contains(HttpStatus.OK);
        responses.stream().filter(r -> r.getStatusCode() != HttpStatus.OK)
                .forEach(r -> assertProblem(r, HttpStatus.CONFLICT, "concurrent-modification"));
        assertThat(read(get(householdPath(id), ana), HouseholdResponse.class).name()).startsWith("Piso ");
    }

    // ---------------------------------------------------------------- utilidades

    private ResponseEntity<String> raw(HttpMethod method, String path, TestUser user, String body) {
        HttpHeaders headers = user.bearer();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private List<MemberResponse> members(TestUser user, UUID householdId) {
        return List.of(read(get(membersPath(householdId), user), MemberResponse[].class));
    }

    /** Lanza todas las llamadas a la vez (tras un latch) y devuelve las respuestas en el mismo orden. */
    private static List<ResponseEntity<String>> concurrently(List<Callable<ResponseEntity<String>>> calls)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(calls.size())) {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (Callable<ResponseEntity<String>> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                responses.add(future.get());
            }
            return responses;
        }
    }
}
