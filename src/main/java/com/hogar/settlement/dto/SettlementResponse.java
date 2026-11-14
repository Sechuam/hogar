package com.hogar.settlement.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SettlementResponse(UUID id, UUID fromUserId, UUID toUserId, BigDecimal amount, String currency,
                                 LocalDate date, String note, UUID createdBy, Instant createdAt) {
}
