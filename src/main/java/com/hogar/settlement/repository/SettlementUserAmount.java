package com.hogar.settlement.repository;

import java.math.BigDecimal;
import java.util.UUID;

/** Proyección de una suma de liquidaciones agrupada por usuario (escala 2). */
public interface SettlementUserAmount {

    UUID getUserId();

    BigDecimal getTotal();
}
