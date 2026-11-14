package com.hogar.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Texto con al menos un carácter que no sea espacio en blanco <b>según Java</b>
 * ({@link Character#isWhitespace}, lo mismo que {@link String#isBlank()} en las entidades).
 * {@code @NotBlank} usa {@code trim()} (solo {@code <= U+0020}): un título de espacios Unicode
 * (U+2003…) lo pasaba y la entidad lo rechazaba con un 500. {@code null} es válido: combínalo
 * con {@code @NotNull} si el campo es obligatorio. Cuantificador posesivo → coste lineal.
 */
@Documented
@Constraint(validatedBy = {})
@Pattern(regexp = "(?s)\\p{javaWhitespace}*+\\P{javaWhitespace}.*")
@ReportAsSingleViolation
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT,
        ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface HasText {

    String message() default "no debe estar en blanco";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
