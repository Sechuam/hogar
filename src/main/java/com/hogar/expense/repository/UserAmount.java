package com.hogar.expense.repository;

import java.math.BigDecimal;
import java.util.UUID;

/** Proyección de una suma agrupada por usuario. */
public interface UserAmount {

    UUID getUserId();

    BigDecimal getTotal();
}
