package com.hogar.household.dto;

import com.hogar.household.domain.SplitPolicy;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** PATCH: los campos {@code null} no se modifican. Solo OWNER. La moneda no es editable. */
public record UpdateHouseholdRequest(
        @Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*") String name,
        SplitPolicy splitPolicy) {
}
