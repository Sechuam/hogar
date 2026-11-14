package com.hogar.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.household.dto.ActiveMember;
import com.hogar.household.dto.HouseholdSplitInfo;
import com.hogar.household.service.HouseholdQueries;
import com.hogar.settlement.domain.Settlement;
import com.hogar.settlement.dto.SettlementRequest;
import com.hogar.settlement.dto.SettlementResponse;
import com.hogar.settlement.repository.SettlementRepository;
import com.hogar.shared.error.BadRequestException;
import com.hogar.shared.error.BusinessRuleException;
import com.hogar.shared.error.DomainException;
import com.hogar.shared.error.ForbiddenException;
import com.hogar.shared.error.NotFoundException;
import com.hogar.shared.security.MembershipGuard;
import com.hogar.shared.web.PageResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 3, 5);

    @Mock
    private SettlementRepository settlements;
    @Mock
    private MembershipGuard guard;
    @Mock
    private HouseholdQueries households;

    private SettlementService service;
    private final UUID householdId = UUID.randomUUID();
    /** OWNER. */
    private final UUID ana = UUID.randomUUID();
    private final UUID luis = UUID.randomUUID();
    private final UUID eva = UUID.randomUUID();
    /** Ex-miembro. */
    private final UUID former = UUID.randomUUID();
    /** Nunca fue miembro. */
    private final UUID stranger = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SettlementService(settlements, guard, households, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(households.getSplitInfo(householdId)).thenReturn(new HouseholdSplitInfo(householdId,
                SplitPolicy.EQUAL, "EUR", List.of(
                        new ActiveMember(ana, HouseholdRole.OWNER, NOW.minusSeconds(30)),
                        new ActiveMember(luis, HouseholdRole.MEMBER, NOW.minusSeconds(20)),
                        new ActiveMember(eva, HouseholdRole.MEMBER, NOW.minusSeconds(10)))));
        for (UUID member : List.of(ana, luis, eva, former)) {
            lenient().when(households.isCurrentOrFormerMember(householdId, member)).thenReturn(true);
        }
        lenient().when(households.isCurrentOrFormerMember(householdId, stranger)).thenReturn(false);
        lenient().when(settlements.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static SettlementRequest request(UUID from, UUID to, String amount, String note) {
        return new SettlementRequest(from, to, new BigDecimal(amount), DATE, note);
    }

    private Settlement stored(UUID from, UUID to, UUID createdBy) {
        Settlement s = Settlement.create(householdId, from, to, new BigDecimal("12.50"), "EUR", DATE, null,
                createdBy, NOW);
        lenient().when(settlements.findByIdAndHouseholdId(s.getId(), householdId)).thenReturn(Optional.of(s));
        lenient().when(settlements.findForUpdate(s.getId(), householdId)).thenReturn(Optional.of(s));
        return s;
    }

    private static void assertCode(Throwable error, String code) {
        assertThat(((DomainException) error).getCode()).isEqualTo(code);
    }

    // --- create ---

    @Test
    void payerRegistersPaymentWithHouseholdCurrencyAndClock() {
        SettlementResponse response = service.create(householdId, luis, request(luis, ana, "12.5", "  Bizum "));

        assertThat(response.fromUserId()).isEqualTo(luis);
        assertThat(response.toUserId()).isEqualTo(ana);
        assertThat(response.amount().toPlainString()).isEqualTo("12.50");
        assertThat(response.currency()).isEqualTo("EUR");
        assertThat(response.date()).isEqualTo(DATE);
        assertThat(response.note()).isEqualTo("Bizum");
        assertThat(response.createdBy()).isEqualTo(luis);
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.id()).isNotNull();
        verify(settlements).save(any(Settlement.class));
    }

    @Test
    void receiverCanRegisterPayment() {
        assertThat(service.create(householdId, ana, request(luis, ana, "1.00", null)).createdBy()).isEqualTo(ana);
    }

    @Test
    void ownerCanRegisterPaymentBetweenOtherMembers() {
        SettlementResponse response = service.create(householdId, ana, request(luis, eva, "3.00", null));

        assertThat(response.createdBy()).isEqualTo(ana);
        assertThat(response.fromUserId()).isEqualTo(luis);
    }

    @Test
    void formerMemberCanBeAParty() {
        assertThat(service.create(householdId, luis, request(former, luis, "7.00", null)).fromUserId())
                .isEqualTo(former);
    }

    @Test
    void memberWhoIsNotAPartyCannotRegister() {
        assertThatThrownBy(() -> service.create(householdId, eva, request(luis, ana, "1.00", null)))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(e -> assertCode(e, SettlementService.SETTLEMENT_FORBIDDEN));
        verify(households, never()).isCurrentOrFormerMember(any(), any());
        verify(settlements, never()).save(any());
    }

    @Test
    void forbiddenWinsOverUnknownParties() {
        assertThatThrownBy(() -> service.create(householdId, eva, request(stranger, ana, "1.00", null)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void payerMustBeCurrentOrFormerMember() {
        assertThatThrownBy(() -> service.create(householdId, ana, request(stranger, ana, "1.00", null)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertCode(e, SettlementService.PARTY_NOT_MEMBER));
        verify(settlements, never()).save(any());
    }

    @Test
    void receiverMustBeCurrentOrFormerMember() {
        assertThatThrownBy(() -> service.create(householdId, luis, request(luis, stranger, "1.00", null)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertCode(e, SettlementService.PARTY_NOT_MEMBER));
        verify(settlements, never()).save(any());
    }

    @Test
    void nonMemberGetsNotFoundBeforeAnything() {
        doThrow(new NotFoundException("household-not-found", "x")).when(guard).requireMember(householdId, stranger);

        assertThatThrownBy(() -> service.create(householdId, stranger, request(stranger, ana, "1.00", null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.list(householdId, stranger, null, null, null, 0, 20))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.get(householdId, stranger, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.delete(householdId, stranger, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(households, settlements);
    }

    // --- list / get ---

    @Test
    void listRejectsInvertedDateRange() {
        assertThatThrownBy(() -> service.list(householdId, ana, DATE, DATE.minusDays(1), null, 0, 20))
                .isInstanceOf(BadRequestException.class)
                .satisfies(e -> assertCode(e, SettlementService.INVALID_DATE_RANGE));
        verifyNoInteractions(settlements);
    }

    @Test
    void listPassesFiltersAndMapsPage() {
        Settlement s = Settlement.create(householdId, luis, ana, new BigDecimal("5"), "EUR", DATE, null, luis, NOW);
        when(settlements.search(householdId, DATE, DATE, luis, PageRequest.of(1, 5)))
                .thenReturn(new PageImpl<>(List.of(s), PageRequest.of(1, 5), 6));

        PageResponse<SettlementResponse> page = service.list(householdId, ana, DATE, DATE, luis, 1, 5);

        assertThat(page.content()).extracting(SettlementResponse::id).containsExactly(s.getId());
        assertThat(page.totalElements()).isEqualTo(6);
        assertThat(page.page()).isEqualTo(1);
    }

    @Test
    void getReturnsSettlementOfTheHousehold() {
        Settlement s = stored(luis, ana, luis);

        assertThat(service.get(householdId, eva, s.getId()).id()).isEqualTo(s.getId());
    }

    @Test
    void getOfUnknownOrForeignSettlementIsNotFound() {
        UUID id = UUID.randomUUID();
        when(settlements.findByIdAndHouseholdId(id, householdId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(householdId, ana, id))
                .isInstanceOf(NotFoundException.class)
                .satisfies(e -> assertCode(e, SettlementService.SETTLEMENT_NOT_FOUND));
    }

    // --- delete ---

    @Test
    void payerReceiverCreatorAndOwnerCanDelete() {
        Settlement byPayer = stored(luis, eva, ana);
        service.delete(householdId, luis, byPayer.getId());
        verify(settlements).delete(byPayer);

        Settlement byReceiver = stored(luis, eva, luis);
        service.delete(householdId, eva, byReceiver.getId());
        verify(settlements).delete(byReceiver);

        // Eva la registró siendo OWNER y hoy es MEMBER sin ser parte: basta con ser la creadora.
        Settlement byCreator = stored(luis, former, eva);
        service.delete(householdId, eva, byCreator.getId());
        verify(settlements).delete(byCreator);

        Settlement byOwner = stored(luis, eva, luis);
        service.delete(householdId, ana, byOwner.getId());
        verify(settlements).delete(byOwner);
    }

    @Test
    void unrelatedMemberCannotDelete() {
        Settlement s = stored(luis, ana, luis);

        assertThatThrownBy(() -> service.delete(householdId, eva, s.getId()))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(e -> assertCode(e, SettlementService.SETTLEMENT_DELETE_FORBIDDEN));
        verify(settlements, never()).delete(any(Settlement.class));
    }

    @Test
    void deleteOfUnknownSettlementIsNotFound() {
        UUID id = UUID.randomUUID();
        when(settlements.findForUpdate(id, householdId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(householdId, ana, id))
                .isInstanceOf(NotFoundException.class)
                .satisfies(e -> assertCode(e, SettlementService.SETTLEMENT_NOT_FOUND));
        verify(settlements, never()).delete(any(Settlement.class));
    }
}
