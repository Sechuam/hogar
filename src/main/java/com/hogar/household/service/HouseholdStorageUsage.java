package com.hogar.household.service;

import java.util.UUID;

/**
 * Puerto que implementa la feature invoice para informar del espacio usado por un hogar. Así
 * household puede mostrar el uso del plan sin depender de invoice (la dependencia va al revés).
 */
public interface HouseholdStorageUsage {

    /** Suma del tamaño de las facturas del hogar, en bytes. */
    long usedBytes(UUID householdId);
}
