package com.hogar.settlement.controller;

import com.hogar.settlement.dto.BalanceResponse;
import com.hogar.settlement.dto.SuggestedTransfersResponse;
import com.hogar.settlement.service.BalanceService;
import com.hogar.shared.security.CurrentUser;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Balances calculados del hogar y pagos sugeridos para saldarlos. */
@RestController
@RequestMapping("/api/households/{householdId}")
class BalanceController {

    private final BalanceService balances;
    private final CurrentUser currentUser;

    BalanceController(BalanceService balances, CurrentUser currentUser) {
        this.balances = balances;
        this.currentUser = currentUser;
    }

    @GetMapping("/balances")
    BalanceResponse balances(@PathVariable UUID householdId) {
        return balances.balances(householdId, currentUser.id());
    }

    /** Ruta literal: Spring la prefiere a {@code /settlements/{settlementId}}. */
    @GetMapping("/settlements/suggested")
    SuggestedTransfersResponse suggested(@PathVariable UUID householdId) {
        return balances.suggestions(householdId, currentUser.id());
    }
}
