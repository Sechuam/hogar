package com.hogar.settlement.dto;

import java.util.List;

/**
 * @param members miembros activos (aunque estén a 0) y ex-miembros con algún importe; Σ balance == 0
 * @param settled {@code true} si todos los balances son 0
 */
public record BalanceResponse(String currency, List<MemberBalance> members, boolean settled) {
}
