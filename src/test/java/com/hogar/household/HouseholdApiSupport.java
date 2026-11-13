package com.hogar.household;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hogar.household.dto.CreateHouseholdRequest;
import com.hogar.household.dto.CreateInvitationRequest;
import com.hogar.household.dto.HouseholdResponse;
import com.hogar.household.dto.InvitationResponse;
import com.hogar.support.AbstractIntegrationTest;
import com.hogar.support.TestUsers.TestUser;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Atajos HTTP para los IT de hogares: cada test crea sus propios usuarios y hogares. */
abstract class HouseholdApiSupport extends AbstractIntegrationTest {

    @Autowired
    protected ObjectMapper json;

    protected ResponseEntity<String> call(HttpMethod method, String path, TestUser user, Object body) {
        HttpHeaders headers = user != null ? user.bearer() : new HttpHeaders();
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    protected ResponseEntity<String> get(String path, TestUser user) {
        return call(HttpMethod.GET, path, user, null);
    }

    protected <T> T read(ResponseEntity<String> response, Class<T> type) {
        try {
            return json.readValue(response.getBody(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Respuesta no es JSON válido: " + response.getBody(), e);
        }
    }

    protected JsonNode tree(ResponseEntity<String> response) {
        try {
            return json.readTree(response.getBody());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Respuesta no es JSON válido: " + response.getBody(), e);
        }
    }

    /** Comprueba estado HTTP y {@code code} del ProblemDetail. */
    protected void assertProblem(ResponseEntity<String> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(status);
        assertThat(tree(response).path("code").asText()).isEqualTo(code);
    }

    /**
     * Cuerpo inválido: JSON roto → {@code bad-request}; JSON válido con un campo de tipo
     * incorrecto → {@code validation-error} con ese campo en {@code errors[]}.
     */
    protected void assertBadBody(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        String code = tree(response).path("code").asText();
        assertThat(code).isIn("bad-request", "validation-error");
        if (code.equals("validation-error")) {
            assertThat(tree(response).path("errors")).isNotEmpty();
        }
    }

    protected HouseholdResponse createHousehold(TestUser owner, String name) {
        ResponseEntity<String> response = call(HttpMethod.POST, "/api/households", owner,
                new CreateHouseholdRequest(name, null, null));
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return read(response, HouseholdResponse.class);
    }

    protected InvitationResponse invite(TestUser owner, UUID householdId, Integer ttlHours, Integer maxUses) {
        ResponseEntity<String> response = call(HttpMethod.POST, invitationsPath(householdId), owner,
                new CreateInvitationRequest(ttlHours, maxUses));
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return read(response, InvitationResponse.class);
    }

    protected ResponseEntity<String> accept(TestUser user, String code) {
        return call(HttpMethod.POST, "/api/invitations/" + code + "/accept", user, null);
    }

    /** Une a {@code user} al hogar como MEMBER mediante una invitación nueva. */
    protected void join(TestUser owner, UUID householdId, TestUser user) {
        ResponseEntity<String> response = accept(user, invite(owner, householdId, null, null).code());
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    protected static String householdPath(UUID householdId) {
        return "/api/households/" + householdId;
    }

    protected static String membersPath(UUID householdId) {
        return householdPath(householdId) + "/members";
    }

    protected static String memberPath(UUID householdId, UUID userId) {
        return membersPath(householdId) + "/" + userId;
    }

    protected static String invitationsPath(UUID householdId) {
        return householdPath(householdId) + "/invitations";
    }
}
