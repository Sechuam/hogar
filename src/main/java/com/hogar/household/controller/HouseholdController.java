package com.hogar.household.controller;

import com.hogar.household.dto.CreateHouseholdRequest;
import com.hogar.household.dto.HouseholdResponse;
import com.hogar.household.dto.PlanResponse;
import com.hogar.household.dto.UpdateHouseholdRequest;
import com.hogar.household.service.HouseholdService;
import com.hogar.shared.config.ApiDoc;
import com.hogar.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/households")
@Tag(name = ApiDoc.TAG_HOUSEHOLDS)
class HouseholdController {

    private final HouseholdService households;
    private final CurrentUser currentUser;

    HouseholdController(HouseholdService households, CurrentUser currentUser) {
        this.households = households;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Crear un hogar",
            description = "Quien lo crea queda como OWNER. `splitPolicy` (por defecto `EQUAL`) y `currency` "
                    + "(ISO-4217, por defecto `EUR`) son opcionales; la moneda no se puede cambiar después.")
    @ApiResponse(responseCode = "201", description = "Hogar creado.",
            headers = @Header(name = "Location", description = "URL del hogar creado", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = ApiDoc.VALIDATION_ERROR + ApiDoc.NL + ApiDoc.MALFORMED_BODY)
    @ApiResponse(responseCode = "422", description = ApiDoc.PLAN_HOUSEHOLD_LIMIT)
    @PostMapping
    ResponseEntity<HouseholdResponse> create(@Valid @RequestBody CreateHouseholdRequest request) {
        HouseholdResponse created = households.create(currentUser.id(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Listar mis hogares",
            description = "Hogares en los que el usuario es miembro activo, con su rol y el número de miembros. "
                    + "Sin paginar: un usuario pertenece a pocos hogares.")
    @ApiResponse(responseCode = "200", description = "Hogares del usuario (puede ser una lista vacía).")
    @GetMapping
    List<HouseholdResponse> listMine() {
        return households.listMine(currentUser.id());
    }

    @Operation(summary = "Consultar un hogar")
    @ApiResponse(responseCode = "200", description = "Hogar con el rol del usuario en él.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND)
    @GetMapping("/{householdId}")
    HouseholdResponse get(@Parameter(description = "ID del hogar") @PathVariable UUID householdId) {
        return households.get(householdId, currentUser.id());
    }

    @Operation(summary = "Modificar un hogar (OWNER)",
            description = "Actualización parcial: los campos ausentes o `null` no cambian. Cambiar `splitPolicy` "
                    + "solo afecta a los gastos que se creen (o recalculen) después; los existentes conservan su "
                    + "reparto.")
    @ApiResponse(responseCode = "200", description = "Hogar actualizado.")
    @ApiResponse(responseCode = "400", description = ApiDoc.VALIDATION_ERROR + ApiDoc.NL + ApiDoc.MALFORMED_BODY)
    @ApiResponse(responseCode = "403", description = ApiDoc.OWNER_ROLE_REQUIRED)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND)
    @ApiResponse(responseCode = "409", description = ApiDoc.CONCURRENT_MODIFICATION)
    @PatchMapping("/{householdId}")
    HouseholdResponse update(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Valid @RequestBody UpdateHouseholdRequest request) {
        return households.update(householdId, currentUser.id(), request);
    }

    @Operation(summary = "Consultar el plan del hogar",
            description = "Plan (FREE o PREMIUM), sus límites (`null` = sin límite) y el uso actual: miembros, "
                    + "espacio de facturas y primer día visible en los listados de gastos y liquidaciones. "
                    + "El plan no se cambia por API: no hay pagos (D-068).")
    @ApiResponse(responseCode = "200", description = "Plan, límites y uso.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND)
    @GetMapping("/{householdId}/plan")
    PlanResponse plan(@Parameter(description = "ID del hogar") @PathVariable UUID householdId) {
        return households.plan(householdId, currentUser.id());
    }
}
