package com.hogar.settlement.service;

import com.hogar.settlement.domain.Settlement;
import com.hogar.settlement.dto.SettlementResponse;

final class SettlementMapper {

    private SettlementMapper() {
    }

    static SettlementResponse toResponse(Settlement s) {
        return new SettlementResponse(s.getId(), s.getFromUserId(), s.getToUserId(), s.getAmount(), s.getCurrency(),
                s.getDate(), s.getNote(), s.getCreatedBy(), s.getCreatedAt());
    }
}
