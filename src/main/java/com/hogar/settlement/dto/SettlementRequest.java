package com.hogar.settlement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
@Schema(description = "Registro de un pago hecho fuera de la app (la app no mueve dinero).")
public record SettlementRequest(
        @Schema(description = "Quién pagó (miembro actual o antiguo).",
                example = "7b2e4c1a-9d3f-4e6a-8b5c-2d1e0f9a8b7c")
        @NotNull UUID fromUserId,
        @Schema(description = "Quién recibió (miembro actual o antiguo); distinto de fromUserId.",
                example = "c4d5e6f7-1a2b-4c3d-8e9f-5a6b7c8d9e0f")
        @NotNull UUID toUserId,
        @Schema(description = "Importe pagado, de 0.01 a 99999999999999999.99 (puede superar lo debido). Decimal con exactamente 2 decimales.",
                example = "42.18")
        @NotNull @DecimalMin("0.01") @DecimalMax("99999999999999999.99") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @Schema(description = "Fecha del pago.", example = "2026-03-15")
        @NotNull LocalDate date,
        @Schema(description = "Nota opcional (máx. 200); si viene, con algún carácter no blanco.",
                example = "Bizum marzo")
        @HasText @Size(max = 200) String note) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "quien paga y quien recibe deben ser personas distintas")
    public boolean isDistinctParties() {
        return fromUserId == null || !fromUserId.equals(toUserId);
    }
}
