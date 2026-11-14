package com.hogar.settlement.dto;

import com.hogar.shared.validation.HasText;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Registro de "{@code fromUserId} pagó {@code amount} a {@code toUserId}" fuera de la app (Bizum,
 * transferencia, efectivo). La app no mueve dinero: solo lo anota para los balances.
 *
 * @param amount hasta el límite de {@code numeric(19,2)}, no el de un gasto: un pago sugerido puede
 *               sumar varios gastos máximos y debe poder registrarse de una vez
 * @param note   opcional; si viene, con algún carácter no blanco
 */
public record SettlementRequest(
        @NotNull UUID fromUserId,
        @NotNull UUID toUserId,
        @NotNull @DecimalMin("0.01") @DecimalMax("99999999999999999.99") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotNull LocalDate date,
        @HasText @Size(max = 200) String note) {

    @AssertTrue(message = "quien paga y quien recibe deben ser personas distintas")
    public boolean isDistinctParties() {
        return fromUserId == null || !fromUserId.equals(toUserId);
    }
}
