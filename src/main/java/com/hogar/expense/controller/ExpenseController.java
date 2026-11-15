package com.hogar.expense.controller;

import com.hogar.expense.domain.ExpenseCategory;
import com.hogar.expense.dto.ExpenseRequest;
import com.hogar.expense.dto.ExpenseResponse;
import com.hogar.expense.service.ExpenseService;
import com.hogar.shared.config.ApiDoc;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = ApiDoc.TAG_EXPENSES)
class ExpenseController {

    private final ExpenseService expenses;
    private final CurrentUser currentUser;

    ExpenseController(ExpenseService expenses, CurrentUser currentUser) {
        this.expenses = expenses;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Registrar un gasto común",
            description = "Se reparte al instante entre los miembros activos según la política del hogar: EQUAL a "
                    + "partes iguales; PROPORTIONAL según los ingresos del mes de `date` (si nadie tiene ingresos se "
                    + "aplica EQUAL y se avisa en `warnings`). Los céntimos sobrantes se asignan por mayor resto, así "
                    + "que la suma de las partes es siempre exactamente el importe. `paidBy` ausente = el usuario "
                    + "autenticado. El reparto queda congelado: cambiar luego la política o los ingresos no lo altera.")
    @ApiResponse(responseCode = "201", description = "Gasto creado con su reparto.",
            headers = @Header(name = "Location", description = "URL del gasto creado", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = ApiDoc.VALIDATION_ERROR + ApiDoc.NL + ApiDoc.MALFORMED_BODY)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND)
    @ApiResponse(responseCode = "422", description = "`payer-not-member`: `paidBy` no es miembro activo del hogar.")
    @PostMapping
    ResponseEntity<ExpenseResponse> create(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Valid @RequestBody ExpenseRequest request) {
        ExpenseResponse created = expenses.create(householdId, currentUser.id(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** {@code size} fuera de 1..100 o {@code page} negativa → 400 (no se acota en silencio). */
    @Operation(summary = "Listar los gastos del hogar",
            description = "Filtros opcionales y combinables. Orden: `date` descendente y, a igualdad, el más "
                    + "reciente primero." + ApiDoc.FREE_HISTORY_WINDOW)
    @ApiResponse(responseCode = "200", description = "Página de gastos.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PAGINATION + ApiDoc.NL + ApiDoc.INVALID_DATE_RANGE)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND)
    @GetMapping
    PageResponse<ExpenseResponse> list(@Parameter(description = "ID del hogar") @PathVariable UUID householdId,
                                       @Parameter(description = "Desde esta fecha (incluida)", example = "2026-03-01") @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
                                       @Parameter(description = "Hasta esta fecha (incluida)", example = "2026-03-31") @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
                                       @Parameter(description = "Solo esta categoría") @RequestParam(required = false) ExpenseCategory category,
                                       @Parameter(description = "Solo los pagados por este usuario") @RequestParam(required = false) UUID paidBy,
                                       @Parameter(description = ApiDoc.PAGE) @RequestParam(defaultValue = "0") @Min(0) @Max(PageResponse.MAX_PAGE) int page,
                                       @Parameter(description = ApiDoc.SIZE) @RequestParam(defaultValue = "20") @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return expenses.list(householdId, currentUser.id(), from, to, category, paidBy, page, size);
    }

    @Operation(summary = "Consultar un gasto", description = "Incluye el reparto (`shares`) congelado.")
    @ApiResponse(responseCode = "200", description = "Gasto con su reparto.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL + ApiDoc.EXPENSE_NOT_FOUND)
    @GetMapping("/{expenseId}")
    ExpenseResponse get(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID del gasto") @PathVariable UUID expenseId) {
        return expenses.get(householdId, currentUser.id(), expenseId);
    }

    @Operation(summary = "Reemplazar un gasto (pagador, creador u OWNER)",
            description = "Si cambian `amount`, `date` o `paidBy` se recalcula el reparto con la política, los "
                    + "ingresos y los miembros actuales; si solo cambian descripción o categoría, el reparto no se "
                    + "toca. `paidBy` ausente conserva el pagador actual (D-042).")
    @ApiResponse(responseCode = "200", description = "Gasto actualizado.")
    @ApiResponse(responseCode = "400", description = ApiDoc.VALIDATION_ERROR + ApiDoc.NL + ApiDoc.MALFORMED_BODY)
    @ApiResponse(responseCode = "403", description = "`expense-edit-forbidden`: solo el pagador, quien lo creó o un "
            + "OWNER pueden modificarlo.")
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL + ApiDoc.EXPENSE_NOT_FOUND)
    @ApiResponse(responseCode = "409", description = ApiDoc.CONCURRENT_MODIFICATION)
    @ApiResponse(responseCode = "422", description = "`payer-not-member`: `paidBy` no es miembro activo del hogar.")
    @PutMapping("/{expenseId}")
    ExpenseResponse update(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID del gasto") @PathVariable UUID expenseId,
                           @Valid @RequestBody ExpenseRequest request) {
        return expenses.update(householdId, currentUser.id(), expenseId, request);
    }

    @Operation(summary = "Borrar un gasto (pagador, creador u OWNER)",
            description = "Borra también su factura adjunta, si la tiene.")
    @ApiResponse(responseCode = "204", description = "Gasto borrado.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "403", description = "`expense-edit-forbidden`: solo el pagador, quien lo creó o un "
            + "OWNER pueden borrarlo.")
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL + ApiDoc.EXPENSE_NOT_FOUND)
    @ApiResponse(responseCode = "409", description = ApiDoc.CONCURRENT_MODIFICATION)
    @DeleteMapping("/{expenseId}")
    ResponseEntity<Void> delete(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID del gasto") @PathVariable UUID expenseId) {
        expenses.delete(householdId, currentUser.id(), expenseId);
        return ResponseEntity.noContent().build();
    }
}
