package com.hogar.household;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.household.dto.CreateHouseholdRequest;
import com.hogar.household.dto.HouseholdResponse;
import com.hogar.household.dto.UpdateHouseholdRequest;
import com.hogar.support.TestUsers.TestUser;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class HouseholdIT extends HouseholdApiSupport {

    @Test
    void createsHouseholdWithLocationAndCreatorAsOwner() {
        TestUser ana = users().create("Ana");

        ResponseEntity<String> response = call(HttpMethod.POST, "/api/households", ana,
                new CreateHouseholdRequest("Piso Lavapiés", SplitPolicy.PROPORTIONAL, "USD"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        HouseholdResponse created = read(response, HouseholdResponse.class);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().getPath()).isEqualTo(householdPath(created.id()));
        assertThat(created.name()).isEqualTo("Piso Lavapiés");
        assertThat(created.splitPolicy()).isEqualTo(SplitPolicy.PROPORTIONAL);
        assertThat(created.currency()).isEqualTo("USD");
        assertThat(created.myRole()).isEqualTo(HouseholdRole.OWNER);
        assertThat(created.memberCount()).isEqualTo(1);

        ResponseEntity<String> fetched = get(householdPath(created.id()), ana);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        // createdAt se compara aparte: PostgreSQL guarda microsegundos y el reloj puede dar nanos.
        assertThat(read(fetched, HouseholdResponse.class))
                .usingRecursiveComparison().ignoringFields("createdAt").isEqualTo(created);
    }

    @Test
    void appliesDefaults() {
        HouseholdResponse created = createHousehold(users().create("Ana"), "Casa");

        assertThat(created.splitPolicy()).isEqualTo(SplitPolicy.EQUAL);
        assertThat(created.currency()).isEqualTo("EUR");
    }

    @Test
    void listsOnlyMyActiveHouseholdsWithRoleAndMemberCount() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        HouseholdResponse anas = createHousehold(ana, "De Ana");
        HouseholdResponse luiss = createHousehold(luis, "De Luis");
        createHousehold(users().create("Eva"), "De Eva");
        join(ana, anas.id(), luis);

        HouseholdResponse[] list = read(get("/api/households", luis), HouseholdResponse[].class);

        assertThat(list).extracting(HouseholdResponse::id).containsExactly(luiss.id(), anas.id());
        assertThat(list).extracting(HouseholdResponse::myRole).containsExactly(HouseholdRole.OWNER, HouseholdRole.MEMBER);
        assertThat(list).extracting(HouseholdResponse::memberCount).containsExactly(1, 2);

        call(HttpMethod.POST, householdPath(anas.id()) + "/leave", luis, null);
        assertThat(read(get("/api/households", luis), HouseholdResponse[].class))
                .extracting(HouseholdResponse::id).containsExactly(luiss.id());
    }

    @Test
    void ownerPatchesNameAndPolicy() {
        TestUser ana = users().create("Ana");
        HouseholdResponse created = createHousehold(ana, "Piso");

        ResponseEntity<String> response = call(HttpMethod.PATCH, householdPath(created.id()), ana,
                new UpdateHouseholdRequest("Casa", null));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(response, HouseholdResponse.class).name()).isEqualTo("Casa");

        HouseholdResponse updated = read(call(HttpMethod.PATCH, householdPath(created.id()), ana,
                new UpdateHouseholdRequest(null, SplitPolicy.PROPORTIONAL)), HouseholdResponse.class);
        assertThat(updated.name()).isEqualTo("Casa");
        assertThat(updated.splitPolicy()).isEqualTo(SplitPolicy.PROPORTIONAL);
        assertThat(updated.myRole()).isEqualTo(HouseholdRole.OWNER);
    }

    @Test
    void memberCannotPatch() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        HouseholdResponse household = createHousehold(ana, "Piso");
        join(ana, household.id(), luis);

        assertProblem(call(HttpMethod.PATCH, householdPath(household.id()), luis,
                new UpdateHouseholdRequest("Mío", null)), HttpStatus.FORBIDDEN, "owner-role-required");
        assertThat(read(get(householdPath(household.id()), luis), HouseholdResponse.class).name()).isEqualTo("Piso");
    }

    @Test
    void otherUsersCannotSeeOrTouchTheHousehold() {
        TestUser ana = users().create("Ana");
        TestUser intruder = users().create("Intruso");
        UUID id = createHousehold(ana, "Piso").id();

        assertProblem(get(householdPath(id), intruder), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(call(HttpMethod.PATCH, householdPath(id), intruder, new UpdateHouseholdRequest("X", null)),
                HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(get(membersPath(id), intruder), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(call(HttpMethod.PATCH, memberPath(id, ana.id()), intruder, Map.of("role", "MEMBER")),
                HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(call(HttpMethod.DELETE, memberPath(id, ana.id()), intruder, null),
                HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(call(HttpMethod.POST, householdPath(id) + "/leave", intruder, null),
                HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(get(invitationsPath(id), intruder), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(call(HttpMethod.POST, invitationsPath(id), intruder, Map.of()),
                HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(call(HttpMethod.DELETE, invitationsPath(id) + "/" + UUID.randomUUID(), intruder, null),
                HttpStatus.NOT_FOUND, "household-not-found");
        assertThat(read(get("/api/households", intruder), HouseholdResponse[].class)).isEmpty();
    }

    @Test
    void unknownHouseholdLooksTheSameAsAForeignOne() {
        TestUser ana = users().create("Ana");
        UUID unknown = UUID.randomUUID();

        assertProblem(get(householdPath(unknown), ana), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(call(HttpMethod.PATCH, householdPath(unknown), ana, new UpdateHouseholdRequest("X", null)),
                HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(get(membersPath(unknown), ana), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(get(invitationsPath(unknown), ana), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(call(HttpMethod.POST, householdPath(unknown) + "/leave", ana, null),
                HttpStatus.NOT_FOUND, "household-not-found");
    }

    @Test
    void formerMemberLosesAccess() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        join(ana, id, luis);

        call(HttpMethod.POST, householdPath(id) + "/leave", luis, null);

        assertProblem(get(householdPath(id), luis), HttpStatus.NOT_FOUND, "household-not-found");
    }

    @Test
    void rejectsInvalidBodies() {
        TestUser ana = users().create("Ana");

        ResponseEntity<String> blank = call(HttpMethod.POST, "/api/households", ana,
                new CreateHouseholdRequest("  ", null, null));
        assertProblem(blank, HttpStatus.BAD_REQUEST, "validation-error");
        assertThat(tree(blank).path("errors").findValuesAsText("field")).contains("name");

        assertProblem(call(HttpMethod.POST, "/api/households", ana, new CreateHouseholdRequest("Piso", null, "eur")),
                HttpStatus.BAD_REQUEST, "validation-error");
        assertProblem(call(HttpMethod.POST, "/api/households", ana,
                new CreateHouseholdRequest("x".repeat(101), null, null)), HttpStatus.BAD_REQUEST, "validation-error");
        assertProblem(call(HttpMethod.POST, "/api/households", ana, Map.of("name", "Piso", "splitPolicy", "RANDOM")),
                HttpStatus.BAD_REQUEST, "bad-request");

        UUID id = createHousehold(ana, "Piso").id();
        assertProblem(call(HttpMethod.PATCH, householdPath(id), ana, new UpdateHouseholdRequest(" ", null)),
                HttpStatus.BAD_REQUEST, "validation-error");
        assertThat(get(householdPath("not-a-uuid"), ana).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void requiresAuthentication() {
        UUID id = createHousehold(users().create("Ana"), "Piso").id();

        assertProblem(get("/api/households", null), HttpStatus.UNAUTHORIZED, "unauthorized");
        assertProblem(call(HttpMethod.POST, "/api/households", null, new CreateHouseholdRequest("Piso", null, null)),
                HttpStatus.UNAUTHORIZED, "unauthorized");
        assertProblem(get(householdPath(id), null), HttpStatus.UNAUTHORIZED, "unauthorized");
        assertProblem(get(membersPath(id), null), HttpStatus.UNAUTHORIZED, "unauthorized");
        assertProblem(call(HttpMethod.POST, "/api/invitations/ABCDEFGHJK/accept", null, null),
                HttpStatus.UNAUTHORIZED, "unauthorized");
    }

    private static String householdPath(String raw) {
        return "/api/households/" + raw;
    }
}
