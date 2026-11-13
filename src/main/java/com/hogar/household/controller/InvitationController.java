package com.hogar.household.controller;

import com.hogar.household.dto.CreateInvitationRequest;
import com.hogar.household.dto.HouseholdResponse;
import com.hogar.household.dto.InvitationResponse;
import com.hogar.household.service.InvitationService;
import com.hogar.shared.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
class InvitationController {

    private final InvitationService invitations;
    private final CurrentUser currentUser;

    InvitationController(InvitationService invitations, CurrentUser currentUser) {
        this.invitations = invitations;
        this.currentUser = currentUser;
    }

    /** El cuerpo es opcional: sin él se usan el TTL por defecto y usos ilimitados. */
    @PostMapping("/api/households/{householdId}/invitations")
    ResponseEntity<InvitationResponse> create(@PathVariable UUID householdId,
                                              @Valid @RequestBody(required = false) CreateInvitationRequest request) {
        CreateInvitationRequest body = request != null ? request : new CreateInvitationRequest(null, null);
        InvitationResponse created = invitations.create(householdId, currentUser.id(), body);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/api/households/{householdId}/invitations")
    List<InvitationResponse> list(@PathVariable UUID householdId) {
        return invitations.list(householdId, currentUser.id());
    }

    @DeleteMapping("/api/households/{householdId}/invitations/{invitationId}")
    ResponseEntity<Void> revoke(@PathVariable UUID householdId, @PathVariable UUID invitationId) {
        invitations.revoke(householdId, currentUser.id(), invitationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/invitations/{code}/accept")
    HouseholdResponse accept(@PathVariable String code) {
        return invitations.accept(code, currentUser.id());
    }
}
