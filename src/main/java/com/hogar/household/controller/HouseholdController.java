package com.hogar.household.controller;

import com.hogar.household.dto.CreateHouseholdRequest;
import com.hogar.household.dto.HouseholdResponse;
import com.hogar.household.dto.UpdateHouseholdRequest;
import com.hogar.household.service.HouseholdService;
import com.hogar.shared.security.CurrentUser;
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
class HouseholdController {

    private final HouseholdService households;
    private final CurrentUser currentUser;

    HouseholdController(HouseholdService households, CurrentUser currentUser) {
        this.households = households;
        this.currentUser = currentUser;
    }

    @PostMapping
    ResponseEntity<HouseholdResponse> create(@Valid @RequestBody CreateHouseholdRequest request) {
        HouseholdResponse created = households.create(currentUser.id(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    List<HouseholdResponse> listMine() {
        return households.listMine(currentUser.id());
    }

    @GetMapping("/{householdId}")
    HouseholdResponse get(@PathVariable UUID householdId) {
        return households.get(householdId, currentUser.id());
    }

    @PatchMapping("/{householdId}")
    HouseholdResponse update(@PathVariable UUID householdId, @Valid @RequestBody UpdateHouseholdRequest request) {
        return households.update(householdId, currentUser.id(), request);
    }
}
