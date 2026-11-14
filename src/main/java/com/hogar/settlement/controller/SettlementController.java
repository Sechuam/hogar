package com.hogar.settlement.controller;

import com.hogar.settlement.dto.SettlementRequest;
import com.hogar.settlement.dto.SettlementResponse;
import com.hogar.settlement.service.SettlementService;
import com.hogar.shared.security.CurrentUser;
import com.hogar.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Liquidaciones ("X pagó a Y fuera de la app"). Los pagos sugeridos están en {@link BalanceController}. */
@RestController
@RequestMapping("/api/households/{householdId}/settlements")
class SettlementController {

    private final SettlementService settlements;
    private final CurrentUser currentUser;

    SettlementController(SettlementService settlements, CurrentUser currentUser) {
        this.settlements = settlements;
        this.currentUser = currentUser;
    }

    @PostMapping
    ResponseEntity<SettlementResponse> create(@PathVariable UUID householdId,
                                              @Valid @RequestBody SettlementRequest request) {
        SettlementResponse created = settlements.create(householdId, currentUser.id(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** {@code size} fuera de 1..100 o {@code page} fuera de 0..{@code MAX_PAGE} → 400 (D-043). */
    @GetMapping
    PageResponse<SettlementResponse> list(@PathVariable UUID householdId,
                                          @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
                                          @RequestParam(required = false) UUID userId,
                                          @RequestParam(defaultValue = "0") @Min(0) @Max(PageResponse.MAX_PAGE) int page,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(PageResponse.MAX_SIZE)
                                          int size) {
        return settlements.list(householdId, currentUser.id(), from, to, userId, page, size);
    }

    @GetMapping("/{settlementId}")
    SettlementResponse get(@PathVariable UUID householdId, @PathVariable UUID settlementId) {
        return settlements.get(householdId, currentUser.id(), settlementId);
    }

    @DeleteMapping("/{settlementId}")
    ResponseEntity<Void> delete(@PathVariable UUID householdId, @PathVariable UUID settlementId) {
        settlements.delete(householdId, currentUser.id(), settlementId);
        return ResponseEntity.noContent().build();
    }
}
