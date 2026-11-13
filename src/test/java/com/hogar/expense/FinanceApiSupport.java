package com.hogar.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hogar.expense.domain.ExpenseCategory;
import com.hogar.expense.dto.ExpenseRequest;
import com.hogar.expense.dto.ExpenseResponse;
import com.hogar.expense.dto.ExpenseResponse.ShareResponse;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.household.dto.CreateHouseholdRequest;
import com.hogar.household.dto.CreateInvitationRequest;
import com.hogar.household.dto.HouseholdResponse;
import com.hogar.household.dto.InvitationResponse;
import com.hogar.household.dto.UpdateHouseholdRequest;
import com.hogar.income.dto.IncomeRequest;
import com.hogar.income.dto.IncomeResponse;
import com.hogar.shared.web.PageResponse;
import com.hogar.support.AbstractIntegrationTest;
import com.hogar.support.TestUsers.TestUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Atajos HTTP para los IT de ingresos y gastos: hogares, miembros, ingresos y gastos creados por la
 * API pública. Cada test crea sus propios datos.
 */
public abstract class FinanceApiSupport extends AbstractIntegrationTest {

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

    protected <T> T read(ResponseEntity<String> response, TypeReference<T> type) {
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

    protected void assertProblem(ResponseEntity<String> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(status);
        assertThat(tree(response).path("code").asText()).as(response.getBody()).isEqualTo(code);
    }

    /** 400 {@code validation-error} con {@code field} entre los errores. */
    protected void assertValidationError(ResponseEntity<String> response, String field) {
        assertProblem(response, HttpStatus.BAD_REQUEST, "validation-error");
        assertThat(tree(response).path("errors").findValuesAsText("field")).contains(field);
    }

    // --- Hogares y miembros ---

    protected UUID createHousehold(TestUser owner, SplitPolicy policy) {
        ResponseEntity<String> response = call(HttpMethod.POST, "/api/households", owner,
                new CreateHouseholdRequest("Piso de " + owner.displayName(), policy, null));
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return read(response, HouseholdResponse.class).id();
    }

    /** Une a cada usuario como MEMBER, en orden (el orden de alta es el desempate del reparto). */
    protected void join(TestUser owner, UUID householdId, TestUser... users) {
        for (TestUser user : users) {
            ResponseEntity<String> invitation = call(HttpMethod.POST, householdPath(householdId) + "/invitations",
                    owner, new CreateInvitationRequest(null, null));
            assertThat(invitation.getStatusCode()).as(invitation.getBody()).isEqualTo(HttpStatus.CREATED);
            String code = read(invitation, InvitationResponse.class).code();
            ResponseEntity<String> accepted = call(HttpMethod.POST, "/api/invitations/" + code + "/accept", user,
                    null);
            assertThat(accepted.getStatusCode()).as(accepted.getBody()).isEqualTo(HttpStatus.OK);
        }
    }

    protected void changePolicy(TestUser owner, UUID householdId, SplitPolicy policy) {
        ResponseEntity<String> response = call(HttpMethod.PATCH, householdPath(householdId), owner,
                new UpdateHouseholdRequest(null, policy));
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    // --- Ingresos ---

    protected IncomeResponse createIncome(TestUser user, UUID householdId, IncomeRequest request) {
        ResponseEntity<String> response = call(HttpMethod.POST, incomesPath(householdId), user, request);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return read(response, IncomeResponse.class);
    }

    /** Ingreso puntual en la fecha indicada. */
    protected IncomeResponse oneOffIncome(TestUser user, UUID householdId, String amount, LocalDate date) {
        return createIncome(user, householdId, new IncomeRequest(new BigDecimal(amount), "Nómina", date, false, null));
    }

    // --- Gastos ---

    protected static ExpenseRequest expense(String amount, LocalDate date, UUID paidBy) {
        return new ExpenseRequest(new BigDecimal(amount), "Compra", ExpenseCategory.GROCERIES, date, paidBy);
    }

    protected ExpenseResponse createExpense(TestUser user, UUID householdId, ExpenseRequest request) {
        ResponseEntity<String> response = call(HttpMethod.POST, expensesPath(householdId), user, request);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return read(response, ExpenseResponse.class);
    }

    protected ExpenseResponse getExpense(TestUser user, UUID householdId, UUID expenseId) {
        ResponseEntity<String> response = get(expensePath(householdId, expenseId), user);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return read(response, ExpenseResponse.class);
    }

    protected PageResponse<ExpenseResponse> listExpenses(TestUser user, UUID householdId, String query) {
        ResponseEntity<String> response = get(expensesPath(householdId) + query, user);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return read(response, new TypeReference<PageResponse<ExpenseResponse>>() { });
    }

    protected PageResponse<IncomeResponse> listIncomes(TestUser user, UUID householdId, String query) {
        ResponseEntity<String> response = get(incomesPath(householdId) + query, user);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return read(response, new TypeReference<PageResponse<IncomeResponse>>() { });
    }

    /** Parte de cada usuario, como texto con escala 2 para comparar exactamente ("22.51"). */
    protected static Map<UUID, String> shares(ExpenseResponse expense) {
        return expense.shares().stream()
                .collect(Collectors.toMap(ShareResponse::userId, s -> s.amount().toPlainString()));
    }

    // --- Rutas ---

    protected static String householdPath(UUID householdId) {
        return "/api/households/" + householdId;
    }

    protected static String incomesPath(UUID householdId) {
        return householdPath(householdId) + "/incomes";
    }

    protected static String incomePath(UUID householdId, UUID incomeId) {
        return incomesPath(householdId) + "/" + incomeId;
    }

    protected static String expensesPath(UUID householdId) {
        return householdPath(householdId) + "/expenses";
    }

    protected static String expensePath(UUID householdId, UUID expenseId) {
        return expensesPath(householdId) + "/" + expenseId;
    }
}
