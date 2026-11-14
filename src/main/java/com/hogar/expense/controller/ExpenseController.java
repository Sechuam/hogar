package com.hogar.expense.controller;

import com.hogar.expense.domain.ExpenseCategory;
import com.hogar.expense.dto.ExpenseRequest;
import com.hogar.expense.dto.ExpenseResponse;
import com.hogar.expense.service.ExpenseService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/households/{householdId}/expenses")
class ExpenseController {

    private final ExpenseService expenses;
    private final CurrentUser currentUser;

    ExpenseController(ExpenseService expenses, CurrentUser currentUser) {
        this.expenses = expenses;
        this.currentUser = currentUser;
    }

    @PostMapping
    ResponseEntity<ExpenseResponse> create(@PathVariable UUID householdId, @Valid @RequestBody ExpenseRequest request) {
        ExpenseResponse created = expenses.create(householdId, currentUser.id(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** {@code size} fuera de 1..100 o {@code page} negativa → 400 (no se acota en silencio). */
    @GetMapping
    PageResponse<ExpenseResponse> list(@PathVariable UUID householdId,
                                       @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
                                       @RequestParam(required = false) ExpenseCategory category,
                                       @RequestParam(required = false) UUID paidBy,
                                       @RequestParam(defaultValue = "0") @Min(0) @Max(PageResponse.MAX_PAGE) int page,
                                       @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return expenses.list(householdId, currentUser.id(), from, to, category, paidBy, page, size);
    }

    @GetMapping("/{expenseId}")
    ExpenseResponse get(@PathVariable UUID householdId, @PathVariable UUID expenseId) {
        return expenses.get(householdId, currentUser.id(), expenseId);
    }

    @PutMapping("/{expenseId}")
    ExpenseResponse update(@PathVariable UUID householdId, @PathVariable UUID expenseId,
                           @Valid @RequestBody ExpenseRequest request) {
        return expenses.update(householdId, currentUser.id(), expenseId, request);
    }

    @DeleteMapping("/{expenseId}")
    ResponseEntity<Void> delete(@PathVariable UUID householdId, @PathVariable UUID expenseId) {
        expenses.delete(householdId, currentUser.id(), expenseId);
        return ResponseEntity.noContent().build();
    }
}
