package com.hogar.household;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.domain.Invitation;
import com.hogar.household.dto.CreateInvitationRequest;
import com.hogar.household.dto.HouseholdResponse;
import com.hogar.household.dto.InvitationResponse;
import com.hogar.household.repository.InvitationRepository;
import com.hogar.household.service.InvitationCodeGenerator;
import com.hogar.support.TestUsers.TestUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class InvitationIT extends HouseholdApiSupport {

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private InvitationCodeGenerator codeGenerator;

    @Autowired
    private Clock clock;

    @Test
    void fullFlowCreateListAcceptAndAlreadyMember() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        Instant before = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);

        ResponseEntity<String> created = call(HttpMethod.POST, invitationsPath(id), ana,
                new CreateInvitationRequest(48, 5));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        InvitationResponse invitation = read(created, InvitationResponse.class);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        assertThat(created.getHeaders().getLocation().getPath())
                .isEqualTo(invitationsPath(id) + "/" + invitation.id());
        assertThat(invitation.code()).matches("[0-9A-HJKMNP-TV-Z]{10}");
        assertThat(invitation.maxUses()).isEqualTo(5);
        assertThat(invitation.uses()).isZero();
        assertThat(invitation.revoked()).isFalse();
        assertThat(Duration.between(invitation.createdAt(), invitation.expiresAt())).isEqualTo(Duration.ofHours(48));
        assertThat(invitation.createdAt()).isAfterOrEqualTo(before);

        // Se acepta aunque el usuario lo teclee en minúsculas y con espacios.
        ResponseEntity<String> accepted = accept(luis, " " + invitation.code().toLowerCase(Locale.ROOT) + " ");
        assertThat(accepted.getStatusCode()).as(accepted.getBody()).isEqualTo(HttpStatus.OK);
        HouseholdResponse joined = read(accepted, HouseholdResponse.class);
        assertThat(joined.id()).isEqualTo(id);
        assertThat(joined.myRole()).isEqualTo(HouseholdRole.MEMBER);
        assertThat(joined.memberCount()).isEqualTo(2);

        assertProblem(accept(luis, invitation.code()), HttpStatus.CONFLICT, "already-member");
        assertProblem(accept(ana, invitation.code()), HttpStatus.CONFLICT, "already-member");

        InvitationResponse[] list = read(get(invitationsPath(id), ana), InvitationResponse[].class);
        assertThat(list).extracting(InvitationResponse::id).containsExactly(invitation.id());
        assertThat(list[0].uses()).isEqualTo(1);
    }

    @Test
    void createsWithDefaultTtlWhenBodyIsEmptyOrMissing() {
        TestUser ana = users().create("Ana");
        UUID id = createHousehold(ana, "Piso").id();

        InvitationResponse noBody = read(call(HttpMethod.POST, invitationsPath(id), ana, null),
                InvitationResponse.class);
        InvitationResponse emptyBody = invite(ana, id, null, null);

        for (InvitationResponse invitation : new InvitationResponse[] {noBody, emptyBody}) {
            assertThat(Duration.between(invitation.createdAt(), invitation.expiresAt())).isEqualTo(Duration.ofDays(7));
            assertThat(invitation.maxUses()).isNull();
        }
        assertThat(noBody.code()).isNotEqualTo(emptyBody.code());
    }

    @Test
    void listsNewestFirstAndOnlyThisHousehold() {
        TestUser ana = users().create("Ana");
        UUID id = createHousehold(ana, "Piso").id();
        UUID other = createHousehold(ana, "Otro").id();
        InvitationResponse first = invite(ana, id, null, null);
        InvitationResponse second = invite(ana, id, null, null);
        invite(ana, other, null, null);

        assertThat(read(get(invitationsPath(id), ana), InvitationResponse[].class))
                .extracting(InvitationResponse::id).containsExactly(second.id(), first.id());
    }

    @Test
    void expiredInvitationIsRejected() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        Instant now = Instant.now(clock);
        Invitation expired = invitationRepository.save(Invitation.create(id, codeGenerator.generate(), ana.id(),
                now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)), null));

        assertProblem(accept(luis, expired.getCode()), HttpStatus.UNPROCESSABLE_ENTITY, "invitation-expired");
        assertProblem(get(householdPath(id), luis), HttpStatus.NOT_FOUND, "household-not-found");
    }

    @Test
    void exhaustedInvitationIsRejected() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser eva = users().create("Eva");
        UUID id = createHousehold(ana, "Piso").id();
        String code = invite(ana, id, null, 1).code();

        assertThat(accept(luis, code).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertProblem(accept(eva, code), HttpStatus.UNPROCESSABLE_ENTITY, "invitation-exhausted");
    }

    @Test
    void revokedInvitationLooksLikeAnUnknownOne() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        UUID id = createHousehold(ana, "Piso").id();
        InvitationResponse invitation = invite(ana, id, null, null);

        String revokePath = invitationsPath(id) + "/" + invitation.id();
        assertThat(call(HttpMethod.DELETE, revokePath, ana, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.DELETE, revokePath, ana, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertProblem(accept(luis, invitation.code()), HttpStatus.NOT_FOUND, "invitation-not-found");
        assertThat(read(get(invitationsPath(id), ana), InvitationResponse[].class)[0].revoked()).isTrue();
    }

    @Test
    void unknownCodeIs404() {
        TestUser luis = users().create("Luis");

        assertProblem(accept(luis, "ZZZZZZZZZZ"), HttpStatus.NOT_FOUND, "invitation-not-found");
        assertProblem(accept(luis, "X".repeat(40)), HttpStatus.NOT_FOUND, "invitation-not-found");
    }

    @Test
    void cannotRevokeAnotherHouseholdsInvitation() {
        TestUser ana = users().create("Ana");
        UUID mine = createHousehold(ana, "Mío").id();
        TestUser eva = users().create("Eva");
        UUID evas = createHousehold(eva, "De Eva").id();
        InvitationResponse evasInvitation = invite(eva, evas, null, null);

        assertProblem(call(HttpMethod.DELETE, invitationsPath(mine) + "/" + evasInvitation.id(), ana, null),
                HttpStatus.NOT_FOUND, "invitation-not-found");
        assertThat(read(get(invitationsPath(evas), eva), InvitationResponse[].class)[0].revoked()).isFalse();
    }

    @Test
    void ttlAboveContractMaximumIsRejectedAndValidationApplies() {
        TestUser ana = users().create("Ana");
        UUID id = createHousehold(ana, "Piso").id();

        assertProblem(call(HttpMethod.POST, invitationsPath(id), ana, new CreateInvitationRequest(0, null)),
                HttpStatus.BAD_REQUEST, "validation-error");
        assertProblem(call(HttpMethod.POST, invitationsPath(id), ana, new CreateInvitationRequest(721, null)),
                HttpStatus.BAD_REQUEST, "validation-error");
        assertProblem(call(HttpMethod.POST, invitationsPath(id), ana, new CreateInvitationRequest(null, 0)),
                HttpStatus.BAD_REQUEST, "validation-error");

        InvitationResponse max = invite(ana, id, 720, null);
        assertThat(Duration.between(max.createdAt(), max.expiresAt())).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void listingKeepsEveryPendingInvitationButOnlyTheMostRecentHistory() {
        TestUser ana = users().create("Ana");
        UUID id = createHousehold(ana, "Piso").id();
        InvitationResponse oldestPending = invite(ana, id, null, null);
        List<UUID> revoked = new ArrayList<>();
        for (int i = 0; i < 22; i++) {
            InvitationResponse invitation = invite(ana, id, null, null);
            ResponseEntity<String> revoke = call(HttpMethod.DELETE, invitationsPath(id) + "/" + invitation.id(), ana, null);
            assertThat(revoke.getStatusCode()).as(revoke.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
            revoked.add(invitation.id());
        }
        InvitationResponse newestPending = invite(ana, id, null, null);

        InvitationResponse[] list = read(get(invitationsPath(id), ana), InvitationResponse[].class);

        // 2 vigentes + las 20 revocadas más recientes; las 2 revocadas más antiguas quedan fuera.
        assertThat(list).hasSize(22);
        assertThat(list[0].id()).isEqualTo(newestPending.id());
        assertThat(list[list.length - 1].id()).isEqualTo(oldestPending.id());
        assertThat(list).extracting(InvitationResponse::id).doesNotContain(revoked.get(0), revoked.get(1));
        assertThat(list).extracting(InvitationResponse::createdAt).isSortedAccordingTo(Comparator.reverseOrder());
    }
}
