package com.hogar.household;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.dto.HouseholdResponse;
import com.hogar.household.dto.MemberResponse;
import com.hogar.household.dto.UpdateMemberRoleRequest;
import com.hogar.support.TestUsers.TestUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class MemberIT extends HouseholdApiSupport {

    private ResponseEntity<String> changeRole(TestUser actor, UUID householdId, UUID target, HouseholdRole role) {
        return call(HttpMethod.PATCH, memberPath(householdId, target), actor, new UpdateMemberRoleRequest(role));
    }

    private ResponseEntity<String> leave(TestUser user, UUID householdId) {
        return call(HttpMethod.POST, householdPath(householdId) + "/leave", user, null);
    }

    private MemberResponse[] members(TestUser user, UUID householdId) {
        ResponseEntity<String> response = get(membersPath(householdId), user);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return read(response, MemberResponse[].class);
    }

    @Test
    void listsMembersWithNamesInJoinOrder() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser eva = users().create("Eva");
        UUID id = createHousehold(ana, "Piso").id();
        join(ana, id, luis);
        join(ana, id, eva);

        MemberResponse[] list = members(eva, id);

        assertThat(list).extracting(MemberResponse::userId).containsExactly(ana.id(), luis.id(), eva.id());
        assertThat(list).extracting(MemberResponse::displayName).containsExactly("Ana", "Luis", "Eva");
        assertThat(list).extracting(MemberResponse::email).containsExactly(ana.email(), luis.email(), eva.email());
        assertThat(list).extracting(MemberResponse::role)
                .containsExactly(HouseholdRole.OWNER, HouseholdRole.MEMBER, HouseholdRole.MEMBER);
    }

    @Test
    void ownerPromotesMemberAndThenCanStepDown() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        join(ana, id, luis);

        ResponseEntity<String> promoted = changeRole(ana, id, luis.id(), HouseholdRole.OWNER);
        assertThat(promoted.getStatusCode()).isEqualTo(HttpStatus.OK);
        MemberResponse body = read(promoted, MemberResponse.class);
        assertThat(body.role()).isEqualTo(HouseholdRole.OWNER);
        assertThat(body.displayName()).isEqualTo("Luis");

        // Con dos OWNERs, Ana puede degradarse; después Luis es el último y no puede.
        assertThat(changeRole(ana, id, ana.id(), HouseholdRole.MEMBER).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertProblem(changeRole(luis, id, luis.id(), HouseholdRole.MEMBER), HttpStatus.UNPROCESSABLE_ENTITY,
                "last-owner");
        assertThat(read(get(householdPath(id), ana), HouseholdResponse.class).myRole()).isEqualTo(HouseholdRole.MEMBER);
    }

    @RepeatedTest(5)
    void twoOwnersDemotingThemselvesAtOnceNeverLeaveTheHouseholdWithoutOwner() throws Exception {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        join(ana, id, luis);
        changeRole(ana, id, luis.id(), HouseholdRole.OWNER);

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<ResponseEntity<String>> a = pool.submit(() -> {
                start.await();
                return changeRole(ana, id, ana.id(), HouseholdRole.MEMBER);
            });
            Future<ResponseEntity<String>> b = pool.submit(() -> {
                start.await();
                return changeRole(luis, id, luis.id(), HouseholdRole.MEMBER);
            });
            start.countDown();

            assertThat(List.of(a.get().getStatusCode(), b.get().getStatusCode()))
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.UNPROCESSABLE_ENTITY);
        }
        assertThat(members(ana, id)).extracting(MemberResponse::role).containsOnlyOnce(HouseholdRole.OWNER);
    }

    @Test
    void soleOwnerCannotDemoteHimself() {
        TestUser ana = users().create("Ana");
        UUID id = createHousehold(ana, "Piso").id();

        assertProblem(changeRole(ana, id, ana.id(), HouseholdRole.MEMBER), HttpStatus.UNPROCESSABLE_ENTITY,
                "last-owner");
    }

    @Test
    void memberCannotDoOwnerActions() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        join(ana, id, luis);

        assertProblem(changeRole(luis, id, luis.id(), HouseholdRole.OWNER), HttpStatus.FORBIDDEN,
                "owner-role-required");
        assertProblem(call(HttpMethod.DELETE, memberPath(id, ana.id()), luis, null), HttpStatus.FORBIDDEN,
                "owner-role-required");
        assertProblem(call(HttpMethod.POST, invitationsPath(id), luis, Map.of()), HttpStatus.FORBIDDEN,
                "owner-role-required");
        assertProblem(get(invitationsPath(id), luis), HttpStatus.FORBIDDEN, "owner-role-required");
        assertProblem(call(HttpMethod.DELETE, invitationsPath(id) + "/" + UUID.randomUUID(), luis, null),
                HttpStatus.FORBIDDEN, "owner-role-required");
    }

    @Test
    void unknownTargetMemberIs404() {
        TestUser ana = users().create("Ana");
        TestUser stranger = users().create("Extraño");
        UUID id = createHousehold(ana, "Piso").id();

        assertProblem(changeRole(ana, id, stranger.id(), HouseholdRole.OWNER), HttpStatus.NOT_FOUND,
                "member-not-found");
        assertProblem(call(HttpMethod.DELETE, memberPath(id, stranger.id()), ana, null), HttpStatus.NOT_FOUND,
                "member-not-found");
    }

    @Test
    void ownerExpelsMemberButNotHimself() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        join(ana, id, luis);

        assertProblem(call(HttpMethod.DELETE, memberPath(id, ana.id()), ana, null), HttpStatus.UNPROCESSABLE_ENTITY,
                "use-leave-endpoint");

        ResponseEntity<String> expelled = call(HttpMethod.DELETE, memberPath(id, luis.id()), ana, null);
        assertThat(expelled.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(members(ana, id)).extracting(MemberResponse::userId).containsExactly(ana.id());
        assertProblem(get(householdPath(id), luis), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(call(HttpMethod.DELETE, memberPath(id, luis.id()), ana, null), HttpStatus.NOT_FOUND,
                "member-not-found");
    }

    @Test
    void memberLeavesAndRejoinsWithANewMembership() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        join(ana, id, luis);

        assertThat(leave(luis, id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(members(ana, id)).extracting(MemberResponse::userId).containsExactly(ana.id());

        join(ana, id, luis);
        MemberResponse[] list = members(luis, id);
        assertThat(list).extracting(MemberResponse::userId).containsExactly(ana.id(), luis.id());
        assertThat(list[1].role()).isEqualTo(HouseholdRole.MEMBER);
    }

    @Test
    void lastOwnerMustHandOverBeforeLeaving() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        join(ana, id, luis);

        assertProblem(leave(ana, id), HttpStatus.UNPROCESSABLE_ENTITY, "last-owner");

        changeRole(ana, id, luis.id(), HouseholdRole.OWNER);
        assertThat(leave(ana, id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(members(luis, id)).extracting(MemberResponse::userId).containsExactly(luis.id());
    }

    @Test
    void soleMemberCanLeaveAndOldInvitationsStopWorking() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        String code = invite(ana, id, null, null).code();

        assertThat(leave(ana, id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertProblem(get(householdPath(id), ana), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(accept(luis, code), HttpStatus.NOT_FOUND, "invitation-not-found");
    }

    @Test
    void rejectsInvalidRoleBodies() {
        TestUser ana = users().create("Ana");
        UUID id = createHousehold(ana, "Piso").id();

        assertProblem(call(HttpMethod.PATCH, memberPath(id, ana.id()), ana, Map.of()), HttpStatus.BAD_REQUEST,
                "validation-error");
        assertBadBody(call(HttpMethod.PATCH, memberPath(id, ana.id()), ana, Map.of("role", "ADMIN")));
    }
}
