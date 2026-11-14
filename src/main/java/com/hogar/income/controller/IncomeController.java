package com.hogar.income.controller;

import com.hogar.income.dto.IncomeRequest;
import com.hogar.income.dto.IncomeResponse;
import com.hogar.income.dto.MonthlyIncomeSummary;
import com.hogar.income.service.IncomeService;
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
@Tag(name = ApiDoc.TAG_INCOMES)
class IncomeController {

    private final IncomeService incomes;
    private final CurrentUser currentUser;

    IncomeController(IncomeService incomes, CurrentUser currentUser) {
        this.incomes = incomes;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Registrar un ingreso propio",
            description = "El ingreso es siempre del usuario autenticado. Puntual (cuenta en el mes de `date`) o "
                    + "recurrente (cada mes desde `date` hasta `endDate`, o indefinido). Los ingresos alimentan el "
                    + "reparto PROPORTIONAL de los gastos que se creen después.")
    @ApiResponse(responseCode = "201", description = "Ingreso creado.",
            headers = @Header(name = "Location", description = "URL del ingreso creado", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = ApiDoc.VALIDATION_ERROR + ApiDoc.NL + ApiDoc.MALFORMED_BODY)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND)
    @PostMapping
    ResponseEntity<IncomeResponse> create(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Valid @RequestBody IncomeRequest request) {
        IncomeResponse created = incomes.create(householdId, currentUser.id(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** {@code size} fuera de 1..100 o {@code page} negativa → 400 (no se acota en silencio). */
    @Operation(summary = "Listar los ingresos del hogar",
            description = "Todos los ingresos del hogar, también los de ex-miembros (transparencia del reparto). "
                    + "Orden: `date` descendente.")
    @ApiResponse(responseCode = "200", description = "Página de ingresos.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PAGINATION)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND)
    @GetMapping
    PageResponse<IncomeResponse> list(@Parameter(description = "ID del hogar") @PathVariable UUID householdId,
                                      @Parameter(description = "Solo los ingresos que cuentan en ese mes (`YYYY-MM`)", schema = @Schema(type = "string", example = "2026-03")) @RequestParam(required = false) YearMonth month,
                                      @Parameter(description = "Solo los ingresos de este usuario") @RequestParam(required = false) UUID userId,
                                      @Parameter(description = ApiDoc.PAGE) @RequestParam(defaultValue = "0") @Min(0) @Max(PageResponse.MAX_PAGE) int page,
                                      @Parameter(description = ApiDoc.SIZE) @RequestParam(defaultValue = "20") @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return incomes.list(householdId, currentUser.id(), userId, month, page, size);
    }

    @Operation(summary = "Resumen mensual de ingresos",
            description = "Ingresos computables de cada miembro activo en el mes (los que usaría el reparto "
                    + "PROPORTIONAL), también los que tienen 0. `percentage` suma exactamente 100.00 (reparto por "
                    + "mayor resto) o es 0 para todos si el total es 0.")
    @ApiResponse(responseCode = "200", description = "Resumen del mes.")
    @ApiResponse(responseCode = "400", description = "`bad-request`: falta `month` o no tiene el formato `YYYY-MM`.")
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND)
    @GetMapping("/summary")
    MonthlyIncomeSummary summary(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "Mes (`YYYY-MM`)", required = true, schema = @Schema(type = "string", example = "2026-03")) @RequestParam YearMonth month) {
        return incomes.summary(householdId, currentUser.id(), month);
    }

    @Operation(summary = "Consultar un ingreso")
    @ApiResponse(responseCode = "200", description = "Ingreso.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL + ApiDoc.INCOME_NOT_FOUND)
    @GetMapping("/{incomeId}")
    IncomeResponse get(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID del ingreso") @PathVariable UUID incomeId) {
        return incomes.get(householdId, currentUser.id(), incomeId);
    }

    @Operation(summary = "Reemplazar un ingreso (solo su dueño)",
            description = "No modifica los gastos ya creados: su reparto es una foto del momento en que se crearon.")
    @ApiResponse(responseCode = "200", description = "Ingreso actualizado.")
    @ApiResponse(responseCode = "400", description = ApiDoc.VALIDATION_ERROR + ApiDoc.NL + ApiDoc.MALFORMED_BODY)
    @ApiResponse(responseCode = "403", description = "`income-owner-required`: solo quien registró el ingreso puede cambiarlo.")
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL + ApiDoc.INCOME_NOT_FOUND)
    @ApiResponse(responseCode = "409", description = ApiDoc.CONCURRENT_MODIFICATION)
    @PutMapping("/{incomeId}")
    IncomeResponse update(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID del ingreso") @PathVariable UUID incomeId,
                          @Valid @RequestBody IncomeRequest request) {
        return incomes.update(householdId, currentUser.id(), incomeId, request);
    }

    @Operation(summary = "Borrar un ingreso (solo su dueño)")
    @ApiResponse(responseCode = "204", description = "Ingreso borrado.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "403", description = "`income-owner-required`: solo quien registró el ingreso puede borrarlo.")
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL + ApiDoc.INCOME_NOT_FOUND)
    @DeleteMapping("/{incomeId}")
    ResponseEntity<Void> delete(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID del ingreso") @PathVariable UUID incomeId) {
        incomes.delete(householdId, currentUser.id(), incomeId);
        return ResponseEntity.noContent().build();
    }
}
