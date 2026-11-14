package com.hogar.settlement.service;

import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.dto.HouseholdSplitInfo;
import com.hogar.household.service.HouseholdQueries;
import com.hogar.settlement.domain.Settlement;
import com.hogar.settlement.dto.SettlementRequest;
import com.hogar.settlement.dto.SettlementResponse;
import com.hogar.settlement.repository.SettlementRepository;
import com.hogar.shared.error.BadRequestException;
import com.hogar.shared.error.BusinessRuleException;
import com.hogar.shared.error.ForbiddenException;
import com.hogar.shared.error.NotFoundException;
import com.hogar.shared.security.MembershipGuard;
import com.hogar.shared.web.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Liquidaciones: anotan que alguien pagó a otro fuera de la app (la app no mueve dinero). Son
 * inmutables: para corregir una se borra y se registra otra. El {@code userId} lo pasa el controller.
 */
@Service
public class SettlementService {

    static final String SETTLEMENT_NOT_FOUND = "settlement-not-found";
    static final String SETTLEMENT_FORBIDDEN = "settlement-forbidden";
    static final String SETTLEMENT_DELETE_FORBIDDEN = "settlement-delete-forbidden";
    static final String PARTY_NOT_MEMBER = "settlement-party-not-member";
    static final String INVALID_DATE_RANGE = "invalid-date-range";

    private final SettlementRepository settlements;
    private final MembershipGuard guard;
    private final HouseholdQueries households;
    private final Clock clock;

    SettlementService(SettlementRepository settlements, MembershipGuard guard, HouseholdQueries households,
                      Clock clock) {
        this.settlements = settlements;
        this.guard = guard;
        this.households = households;
        this.clock = clock;
    }

    /**
     * Registra un pago. Quien registra debe ser una de las partes u OWNER (se comprueba antes que las
     * partes: una petición no autorizada no revela si un usuario fue miembro). Las partes pueden ser
     * ex-miembros: sus deudas siguen siendo del hogar. Se permite pagar más de lo debido.
     *
     * @throws ForbiddenException    {@code settlement-forbidden}
     * @throws BusinessRuleException {@code settlement-party-not-member}
     */
    @Transactional
    public SettlementResponse create(UUID householdId, UUID userId, SettlementRequest request) {
        guard.requireMember(householdId, userId);
        HouseholdSplitInfo info = households.getSplitInfo(householdId);
        boolean isParty = userId.equals(request.fromUserId()) || userId.equals(request.toUserId());
        if (!isParty && !isOwner(info, userId)) {
            throw new ForbiddenException(SETTLEMENT_FORBIDDEN,
                    "Solo quien paga, quien recibe o un OWNER puede registrar una liquidación.");
        }
        requireParty(householdId, request.fromUserId());
        requireParty(householdId, request.toUserId());
        Settlement settlement = Settlement.create(householdId, request.fromUserId(), request.toUserId(),
                request.amount(), info.currency(), request.date(), request.note(), userId, Instant.now(clock));
        return SettlementMapper.toResponse(settlements.save(settlement));
    }

    /**
     * Liquidaciones del hogar, fecha desc y alta desc. {@code userId} filtra por pagador o receptor.
     *
     * @throws BadRequestException {@code invalid-date-range} si {@code from > to}
     */
    @Transactional(readOnly = true)
    public PageResponse<SettlementResponse> list(UUID householdId, UUID userId, LocalDate fromOrNull,
                                                 LocalDate toOrNull, UUID participantOrNull, int page, int size) {
        guard.requireMember(householdId, userId);
        if (fromOrNull != null && toOrNull != null && fromOrNull.isAfter(toOrNull)) {
            throw new BadRequestException(INVALID_DATE_RANGE, "'from' no puede ser posterior a 'to'.");
        }
        return PageResponse.of(settlements.search(householdId, fromOrNull, toOrNull, participantOrNull,
                PageRequest.of(page, size)), SettlementMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public SettlementResponse get(UUID householdId, UUID userId, UUID settlementId) {
        guard.requireMember(householdId, userId);
        return SettlementMapper.toResponse(load(householdId, settlementId));
    }

    /**
     * Anula una liquidación. Puede quien la registró, cualquiera de las dos partes (el receptor puede
     * anular un pago que no recibió) o un OWNER.
     *
     * @throws ForbiddenException {@code settlement-delete-forbidden}
     */
    @Transactional
    public void delete(UUID householdId, UUID userId, UUID settlementId) {
        guard.requireMember(householdId, userId);
        Settlement settlement = load(householdId, settlementId);
        if (!userId.equals(settlement.getCreatedBy()) && !settlement.involves(userId)
                && !isOwner(households.getSplitInfo(householdId), userId)) {
            throw new ForbiddenException(SETTLEMENT_DELETE_FORBIDDEN,
                    "Solo quien la registró, una de las partes o un OWNER puede borrar la liquidación.");
        }
        settlements.delete(settlement);
    }

    private void requireParty(UUID householdId, UUID partyId) {
        if (!households.isCurrentOrFormerMember(householdId, partyId)) {
            throw new BusinessRuleException(PARTY_NOT_MEMBER,
                    "Quien paga y quien recibe deben ser miembros (actuales o antiguos) del hogar.");
        }
    }

    private static boolean isOwner(HouseholdSplitInfo info, UUID userId) {
        return info.activeMembers().stream()
                .anyMatch(m -> m.userId().equals(userId) && m.role() == HouseholdRole.OWNER);
    }

    private Settlement load(UUID householdId, UUID settlementId) {
        return settlements.findByIdAndHouseholdId(settlementId, householdId)
                .orElseThrow(() -> new NotFoundException(SETTLEMENT_NOT_FOUND, "La liquidación no existe."));
    }
}
