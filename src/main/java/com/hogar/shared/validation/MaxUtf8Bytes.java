package com.hogar.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Texto que ocupa como mucho {@link #value()} bytes en UTF-8. {@code @Size} cuenta caracteres
 * UTF-16: para BCrypt, que solo admite 72 <b>bytes</b> (Spring Security lanza
 * {@code IllegalArgumentException} si se pasan), 72 caracteres no ASCII no bastan.
 * {@code null} es válido: combínalo con {@code @NotNull}/{@code @NotBlank}.
 */
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT,
        ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxUtf8Bytes {

    int value();

    String message() default "no debe ocupar más de {value} bytes en UTF-8";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
