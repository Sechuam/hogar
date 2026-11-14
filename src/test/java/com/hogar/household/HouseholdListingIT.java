package com.hogar.household;

import static org.assertj.core.api.Assertions.assertThat;

import com.hogar.household.domain.HouseholdRole;
import com.hogar.household.dto.HouseholdResponse;
import com.hogar.support.TestUsers.TestUser;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code GET /api/households} no hace N+1: los recuentos de miembros salen de una consulta agregada,
 * así que el número de sentencias no depende del número de hogares del usuario (D-033).
 */
class HouseholdListingIT extends HouseholdApiSupport {

    @Autowired
    private EntityManagerFactory emf;

    @Test
    void listingFifteenHouseholdsUsesAsManyStatementsAsListingOne() {
        TestUser eva = users().create("Eva");
        createHousehold(eva, "Único");

        TestUser sara = users().create("Sara");
        TestUser luis = users().create("Luis");
        TestUser marta = users().create("Marta");
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            if (i % 3 == 0) {
                // Sara entra como MEMBER en el hogar de otra persona.
                UUID h = createHousehold(luis, "Ajeno " + i).id();
                join(luis, h, sara);
                created.add(h);
            } else {
                UUID h = createHousehold(sara, "Propio " + i).id();
                if (i % 2 == 0) {
                    join(sara, h, marta);
                }
                created.add(h);
            }
        }
        // Un miembro que se va no cuenta.
        UUID left = created.get(1);
        join(sara, left, luis);
        ResponseEntity<String> leave = call(HttpMethod.POST, householdPath(left) + "/leave", luis, null);
        assertThat(leave.getStatusCode()).as(leave.getBody()).isEqualTo(HttpStatus.NO_CONTENT);

        long oneStatements = statementsFor(() -> assertThat(list(eva)).hasSize(1));
        List<HouseholdResponse> many = new ArrayList<>();
        long manyStatements = statementsFor(() -> many.addAll(list(sara)));

        assertThat(many).extracting(HouseholdResponse::id).containsExactlyElementsOf(created);
        for (int i = 0; i < 15; i++) {
            HouseholdResponse h = many.get(i);
            int expectedCount = i % 3 == 0 ? 2 : (i % 2 == 0 ? 2 : 1);
            assertThat(h.memberCount()).as(h.name()).isEqualTo(expectedCount);
            assertThat(h.myRole()).as(h.name()).isEqualTo(i % 3 == 0 ? HouseholdRole.MEMBER : HouseholdRole.OWNER);
        }
        assertThat(manyStatements).as("sentencias con 15 hogares vs con 1").isEqualTo(oneStatements);
        // membresías del usuario + hogares + recuentos agregados (+ margen para la autenticación)
        assertThat(manyStatements).isLessThanOrEqualTo(4);
    }

    @Test
    void userWithoutHouseholdsGetsEmptyListWithMinimalQueries() {
        TestUser nobody = users().create("Nadie");

        long statements = statementsFor(() -> assertThat(list(nobody)).isEmpty());

        assertThat(statements).isLessThanOrEqualTo(2);
    }

    private List<HouseholdResponse> list(TestUser user) {
        ResponseEntity<String> response = get("/api/households", user);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return List.of(read(response, HouseholdResponse[].class));
    }

    /** Sentencias JDBC preparadas durante {@code action} (estadísticas de Hibernate activadas solo aquí). */
    private long statementsFor(Runnable action) {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        try {
            stats.clear();
            action.run();
            return stats.getPrepareStatementCount();
        } finally {
            stats.setStatisticsEnabled(wasEnabled);
        }
    }
}
