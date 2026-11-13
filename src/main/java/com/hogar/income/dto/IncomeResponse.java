package com.hogar.income.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record IncomeResponse(
        UUID id,
        UUID userId,
        BigDecimal amount,
        String currency,
        String concept,
        LocalDate date,
        boolean recurring,
        LocalDate endDate,
        Instant createdAt) {
}
