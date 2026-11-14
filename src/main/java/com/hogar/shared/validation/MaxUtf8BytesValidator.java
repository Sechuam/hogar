package com.hogar.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

/** Validador de {@link MaxUtf8Bytes}. */
public class MaxUtf8BytesValidator implements ConstraintValidator<MaxUtf8Bytes, CharSequence> {

    private int max;

    @Override
    public void initialize(MaxUtf8Bytes annotation) {
        this.max = annotation.value();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        // Un carácter ocupa al menos 1 byte: si ya hay más caracteres que bytes permitidos, no cabe.
        return value == null
                || (value.length() <= max && value.toString().getBytes(StandardCharsets.UTF_8).length <= max);
    }
}
