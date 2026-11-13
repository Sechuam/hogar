package com.hogar.household.dto;

import com.hogar.household.domain.SplitPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param splitPolicy opcional, por defecto {@code EQUAL}
 * @param currency    opcional, ISO-4217, por defecto {@code EUR}
 */
public record CreateHouseholdRequest(
        @NotBlank @Size(max = 100) String name,
        SplitPolicy splitPolicy,
        @Pattern(regexp = "[A-Z]{3}") String currency) {
}
