package com.hogar.settlement.controller;

import com.hogar.settlement.dto.BalanceResponse;
import com.hogar.settlement.dto.SuggestedTransfersResponse;
import com.hogar.settlement.service.BalanceService;
import com.hogar.shared.config.ApiDoc;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.hogar.shared.security.CurrentUser;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Balances calculados del hogar y pagos sugeridos para saldarlos. */
@RestController
@RequestMapping("/api/households/{householdId}")
@Tag(name = ApiDoc.TAG_BALANCES)
class BalanceController {

    private final BalanceService balances;
    private final CurrentUser currentUser;

    BalanceController(BalanceService balances, CurrentUser currentUser) {
        this.balances = balances;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Balances del hogar",
            description = "Se calculan al momento con todos los gastos y liquidaciones (nunca se guardan): "
                    + "`balance = paid − owed + settlementsSent − settlementsReceived`. Positivo = le deben; "
                    + "negativo = debe. Aparecen los miembros activos (por orden de alta) y después los ex-miembros "
                    + "con algún importe. La suma de todos los balances es siempre 0.00.")
    @ApiResponse(responseCode = "200", description = "Balances.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND)
    @GetMapping("/balances")
    BalanceResponse balances(@Parameter(description = "ID del hogar") @PathVariable UUID householdId) {
        return balances.balances(householdId, currentUser.id());
    }

    /** Ruta literal: Spring la prefiere a {@code /settlements/{settlementId}}. */
    @Operation(summary = "Pagos sugeridos para saldar cuentas",
            description = "Simplificación de deudas determinista sobre los balances actuales (incluye ex-miembros con "
                    + "saldo): como mucho n−1 pagos, nadie paga y cobra a la vez. Lista vacía si todo está saldado. "
                    + "Cada pago se puede registrar después como liquidación.")
    @ApiResponse(responseCode = "200", description = "Pagos sugeridos.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND)
    @GetMapping("/settlements/suggested")
    SuggestedTransfersResponse suggested(@Parameter(description = "ID del hogar") @PathVariable UUID householdId) {
        return balances.suggestions(householdId, currentUser.id());
    }
}
