package com.hogar.settlement.controller;

import com.hogar.settlement.dto.SettlementRequest;
import com.hogar.settlement.dto.SettlementResponse;
import com.hogar.settlement.service.SettlementService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Liquidaciones ("X pagó a Y fuera de la app"). Los pagos sugeridos están en {@link BalanceController}. */
@RestController
@RequestMapping("/api/households/{householdId}/settlements")
@Tag(name = ApiDoc.TAG_SETTLEMENTS)
class SettlementController {

    private final SettlementService settlements;
    private final CurrentUser currentUser;

    SettlementController(SettlementService settlements, CurrentUser currentUser) {
        this.settlements = settlements;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Registrar una liquidación",
            description = "Anota que `fromUserId` pagó `amount` a `toUserId` fuera de la app (Bizum, transferencia, "
                    + "efectivo): la app no mueve dinero. Pueden registrarla las partes o un OWNER; las partes pueden "
                    + "ser ex-miembros con saldo pendiente. Se permite pagar más de lo debido (el balance cambia de "
                    + "signo).")
    @ApiResponse(responseCode = "201", description = "Liquidación registrada.",
            headers = @Header(name = "Location", description = "URL de la liquidación", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = ApiDoc.VALIDATION_ERROR + ApiDoc.NL + ApiDoc.MALFORMED_BODY)
    @ApiResponse(responseCode = "403", description = "`settlement-forbidden`: no eres quien paga, quien recibe ni OWNER.")
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND)
    @ApiResponse(responseCode = "422", description = "`settlement-party-not-member`: quien paga o quien recibe nunca "
            + "ha sido miembro del hogar.")
    @PostMapping
    ResponseEntity<SettlementResponse> create(@Parameter(description = "ID del hogar") @PathVariable UUID householdId,
                                              @Valid @RequestBody SettlementRequest request) {
        SettlementResponse created = settlements.create(householdId, currentUser.id(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** {@code size} fuera de 1..100 o {@code page} fuera de 0..{@code MAX_PAGE} → 400 (D-043). */
    @Operation(summary = "Listar las liquidaciones del hogar",
            description = "Orden: fecha descendente y, a igualdad, la más reciente primero." + ApiDoc.FREE_HISTORY_WINDOW)
    @ApiResponse(responseCode = "200", description = "Página de liquidaciones.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PAGINATION + ApiDoc.NL + ApiDoc.INVALID_DATE_RANGE)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND)
    @GetMapping
    PageResponse<SettlementResponse> list(@Parameter(description = "ID del hogar") @PathVariable UUID householdId,
                                          @Parameter(description = "Desde esta fecha (incluida)", example = "2026-03-01") @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
                                          @Parameter(description = "Hasta esta fecha (incluida)", example = "2026-03-31") @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
                                          @Parameter(description = "Solo aquellas en las que este usuario paga o recibe") @RequestParam(required = false) UUID userId,
                                          @Parameter(description = ApiDoc.PAGE) @RequestParam(defaultValue = "0") @Min(0) @Max(PageResponse.MAX_PAGE) int page,
                                          @Parameter(description = ApiDoc.SIZE) @RequestParam(defaultValue = "20") @Min(1) @Max(PageResponse.MAX_SIZE)
                                          int size) {
        return settlements.list(householdId, currentUser.id(), from, to, userId, page, size);
    }

    @Operation(summary = "Consultar una liquidación")
    @ApiResponse(responseCode = "200", description = "Liquidación.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL + ApiDoc.SETTLEMENT_NOT_FOUND)
    @GetMapping("/{settlementId}")
    SettlementResponse get(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID de la liquidación") @PathVariable UUID settlementId) {
        return settlements.get(householdId, currentUser.id(), settlementId);
    }

    @Operation(summary = "Anular una liquidación",
            description = "Pueden anularla quien la registró, cualquiera de las dos partes (p. ej. quien no recibió "
                    + "el pago) o un OWNER. Las liquidaciones no se editan: se anulan y se registran de nuevo.")
    @ApiResponse(responseCode = "204", description = "Liquidación anulada.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "403", description = "`settlement-delete-forbidden`: no la registraste, no eres parte "
            + "ni OWNER.")
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL + ApiDoc.SETTLEMENT_NOT_FOUND)
    @DeleteMapping("/{settlementId}")
    ResponseEntity<Void> delete(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID de la liquidación") @PathVariable UUID settlementId) {
        settlements.delete(householdId, currentUser.id(), settlementId);
        return ResponseEntity.noContent().build();
    }
}
