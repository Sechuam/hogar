package com.hogar.income.controller;

import com.hogar.income.dto.IncomeRequest;
import com.hogar.income.dto.IncomeResponse;
import com.hogar.income.dto.MonthlyIncomeSummary;
import com.hogar.income.service.IncomeService;
import com.hogar.shared.security.CurrentUser;
import com.hogar.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/households/{householdId}/incomes")
class IncomeController {

    private final IncomeService incomes;
    private final CurrentUser currentUser;

    IncomeController(IncomeService incomes, CurrentUser currentUser) {
        this.incomes = incomes;
        this.currentUser = currentUser;
    }

    @PostMapping
    ResponseEntity<IncomeResponse> create(@PathVariable UUID householdId, @Valid @RequestBody IncomeRequest request) {
        IncomeResponse created = incomes.create(householdId, currentUser.id(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** {@code size} fuera de 1..100 o {@code page} negativa → 400 (no se acota en silencio). */
    @GetMapping
    PageResponse<IncomeResponse> list(@PathVariable UUID householdId,
                                      @RequestParam(required = false) YearMonth month,
                                      @RequestParam(required = false) UUID userId,
                                      @RequestParam(defaultValue = "0") @Min(0) @Max(PageResponse.MAX_PAGE) int page,
                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return incomes.list(householdId, currentUser.id(), userId, month, page, size);
    }

    @GetMapping("/summary")
    MonthlyIncomeSummary summary(@PathVariable UUID householdId, @RequestParam YearMonth month) {
        return incomes.summary(householdId, currentUser.id(), month);
    }

    @GetMapping("/{incomeId}")
    IncomeResponse get(@PathVariable UUID householdId, @PathVariable UUID incomeId) {
        return incomes.get(householdId, currentUser.id(), incomeId);
    }

    @PutMapping("/{incomeId}")
    IncomeResponse update(@PathVariable UUID householdId, @PathVariable UUID incomeId,
                          @Valid @RequestBody IncomeRequest request) {
        return incomes.update(householdId, currentUser.id(), incomeId, request);
    }

    @DeleteMapping("/{incomeId}")
    ResponseEntity<Void> delete(@PathVariable UUID householdId, @PathVariable UUID incomeId) {
        incomes.delete(householdId, currentUser.id(), incomeId);
        return ResponseEntity.noContent().build();
    }
}
