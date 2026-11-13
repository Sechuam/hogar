package com.hogar.household.controller;

import com.hogar.household.dto.MemberResponse;
import com.hogar.household.dto.UpdateMemberRoleRequest;
import com.hogar.household.service.MemberService;
import com.hogar.shared.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/households/{householdId}")
class MemberController {

    private final MemberService members;
    private final CurrentUser currentUser;

    MemberController(MemberService members, CurrentUser currentUser) {
        this.members = members;
        this.currentUser = currentUser;
    }

    @GetMapping("/members")
    List<MemberResponse> list(@PathVariable UUID householdId) {
        return members.list(householdId, currentUser.id());
    }

    @PatchMapping("/members/{userId}")
    MemberResponse changeRole(@PathVariable UUID householdId, @PathVariable UUID userId,
                              @Valid @RequestBody UpdateMemberRoleRequest request) {
        return members.changeRole(householdId, currentUser.id(), userId, request.role());
    }

    @DeleteMapping("/members/{userId}")
    ResponseEntity<Void> remove(@PathVariable UUID householdId, @PathVariable UUID userId) {
        members.remove(householdId, currentUser.id(), userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/leave")
    ResponseEntity<Void> leave(@PathVariable UUID householdId) {
        members.leave(householdId, currentUser.id());
        return ResponseEntity.noContent().build();
    }
}
