package com.hogar.income.service;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;

/** Contrato público de la feature income para otras features (reparto proporcional). */
public interface IncomeQueries {

    /**
     * Ingresos computables por usuario en un mes natural: puntuales con fecha en ese mes +
     * recurrentes vigentes en algún día de ese mes (cuentan una vez por mes). Solo aparecen los
     * usuarios con algún ingreso; escala 2. No comprueba permisos.
     */
    Map<UUID, BigDecimal> monthlyIncomeByUser(UUID householdId, YearMonth month);
}
