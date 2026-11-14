package com.hogar.task.domain.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TaskRotation.nextAssignee")
class TaskRotationTest {

    private static final UUID ANA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BEA = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CAR = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID DAN = UUID.fromString("00000000-0000-0000-0000-00000000000d");
    private static final UUID EVA = UUID.fromString("00000000-0000-0000-0000-00000000000e");
    private static final UUID OUTSIDER = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    private static final List<UUID> FIVE = List.of(ANA, BEA, CAR, DAN, EVA);
    private static final Set<UUID> ALL_FIVE = Set.copyOf(FIVE);

    @Nested
    @DisplayName("dos miembros")
    class TwoMembers {

        private final List<UUID> rotation = List.of(ANA, BEA);
        private final Set<UUID> both = Set.of(ANA, BEA);

        @Test
        @DisplayName("alterna en ambos sentidos")
        void alternates() {
            assertThat(TaskRotation.nextAssignee(rotation, ANA, both)).contains(BEA);
            assertThat(TaskRotation.nextAssignee(rotation, BEA, both)).contains(ANA);
        }

        @Test
        @DisplayName("si el otro abandona el hogar, le vuelve a tocar al actual")
        void otherLeft() {
            assertThat(TaskRotation.nextAssignee(rotation, ANA, Set.of(ANA))).contains(ANA);
        }

        @Test
        @DisplayName("si el actual abandona, pasa al otro")
        void currentLeft() {
            assertThat(TaskRotation.nextAssignee(rotation, ANA, Set.of(BEA))).contains(BEA);
        }
    }

    @Nested
    @DisplayName("cinco miembros")
    class FiveMembers {

        @Test
        @DisplayName("siguiente normal")
        void nextInOrder() {
            assertThat(TaskRotation.nextAssignee(FIVE, BEA, ALL_FIVE)).contains(CAR);
        }

        @Test
        @DisplayName("vuelta circular del último al primero")
        void wrapsAround() {
            assertThat(TaskRotation.nextAssignee(FIVE, EVA, ALL_FIVE)).contains(ANA);
        }

        @Test
        @DisplayName("una vuelta completa visita a todos en orden")
        void fullCycle() {
            UUID current = ANA;
            StringBuilder order = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                current = TaskRotation.nextAssignee(FIVE, current, ALL_FIVE).orElseThrow();
                order.append(FIVE.indexOf(current));
            }
            assertThat(order).hasToString("12340");
        }

        @Test
        @DisplayName("salta a los inactivos (quien abandonó el hogar)")
        void skipsInactive() {
            assertThat(TaskRotation.nextAssignee(FIVE, BEA, Set.of(ANA, BEA, EVA))).contains(EVA);
        }

        @Test
        @DisplayName("salta inactivos también al dar la vuelta")
        void skipsInactiveAcrossWrap() {
            assertThat(TaskRotation.nextAssignee(FIVE, DAN, Set.of(CAR, DAN))).contains(CAR);
        }

        @Test
        @DisplayName("current inactivo: continúa desde su posición, no desde el principio")
        void currentInactive() {
            assertThat(TaskRotation.nextAssignee(FIVE, CAR, Set.of(ANA, BEA, DAN))).contains(DAN);
        }

        @Test
        @DisplayName("current fuera de la lista: empieza por el principio")
        void currentNotInRotation() {
            assertThat(TaskRotation.nextAssignee(FIVE, OUTSIDER, ALL_FIVE)).contains(ANA);
            assertThat(TaskRotation.nextAssignee(FIVE, OUTSIDER, Set.of(CAR, EVA))).contains(CAR);
        }

        @Test
        @DisplayName("sin asignado (null): empieza por el principio")
        void currentNull() {
            assertThat(TaskRotation.nextAssignee(FIVE, null, ALL_FIVE)).contains(ANA);
            assertThat(TaskRotation.nextAssignee(FIVE, null, Set.of(DAN))).contains(DAN);
        }

        @Test
        @DisplayName("solo current activo: vuelve a current")
        void onlyCurrentActive() {
            assertThat(TaskRotation.nextAssignee(FIVE, DAN, Set.of(DAN, OUTSIDER))).contains(DAN);
        }

        @Test
        @DisplayName("ninguno activo: vacío")
        void noneActive() {
            assertThat(TaskRotation.nextAssignee(FIVE, ANA, Set.of(OUTSIDER))).isEmpty();
            assertThat(TaskRotation.nextAssignee(FIVE, ANA, Set.of())).isEmpty();
        }

        @Test
        @DisplayName("activos ajenos a la rotación no se asignan")
        void ignoresActiveOutsideRotation() {
            assertThat(TaskRotation.nextAssignee(FIVE, ANA, Set.of(OUTSIDER, CAR))).contains(CAR);
        }
    }

    @Nested
    @DisplayName("casos límite y validación")
    class EdgeCases {

        @Test
        @DisplayName("un único miembro activo: se repite")
        void singleMember() {
            assertThat(TaskRotation.nextAssignee(List.of(ANA), ANA, Set.of(ANA))).contains(ANA);
            assertThat(TaskRotation.nextAssignee(List.of(ANA), ANA, Set.of(BEA))).isEmpty();
        }

        @Test
        @DisplayName("rotación vacía: vacío")
        void emptyRotation() {
            assertThat(TaskRotation.nextAssignee(List.of(), ANA, Set.of(ANA))).isEmpty();
        }

        @Test
        @DisplayName("no modifica sus argumentos")
        void doesNotMutate() {
            List<UUID> rotation = Arrays.asList(ANA, BEA, CAR);
            TaskRotation.nextAssignee(rotation, BEA, Set.of(CAR));
            assertThat(rotation).containsExactly(ANA, BEA, CAR);
        }

        @Test
        @DisplayName("argumentos nulos o miembros nulos: NullPointerException")
        void nulls() {
            assertThatNullPointerException().isThrownBy(() -> TaskRotation.nextAssignee(null, ANA, Set.of()))
                    .withMessage("rotation");
            assertThatNullPointerException().isThrownBy(() -> TaskRotation.nextAssignee(List.of(ANA), ANA, null))
                    .withMessage("activeMembers");
            assertThatNullPointerException()
                    .isThrownBy(() -> TaskRotation.nextAssignee(Arrays.asList(ANA, null), ANA, Set.of(ANA)));
        }

        @Test
        @DisplayName("miembros repetidos: IllegalArgumentException")
        void duplicates() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TaskRotation.nextAssignee(List.of(ANA, BEA, ANA), ANA, Set.of(ANA)))
                    .withMessageContaining(ANA.toString());
        }
    }
}
