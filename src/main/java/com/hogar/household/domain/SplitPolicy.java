package com.hogar.household.domain;

/** Política de reparto de gastos del hogar. Solo el OWNER puede cambiarla. */
public enum SplitPolicy {
    /** A partes iguales entre los miembros activos. */
    EQUAL,
    /** Proporcional a los ingresos de cada miembro en el mes del gasto. */
    PROPORTIONAL
}
