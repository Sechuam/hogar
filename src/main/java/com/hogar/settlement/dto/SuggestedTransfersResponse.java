package com.hogar.settlement.dto;

import java.util.List;

/** Como mucho n−1 pagos (n = personas con balance distinto de 0); vacío si todo está saldado. */
public record SuggestedTransfersResponse(String currency, List<SuggestedTransfer> transfers) {
}
