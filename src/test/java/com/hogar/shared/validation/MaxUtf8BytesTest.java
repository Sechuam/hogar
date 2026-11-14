package com.hogar.shared.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MaxUtf8BytesTest {

    record Secret(@MaxUtf8Bytes(4) String value) {
    }

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void nullIsValid() {
        assertThat(validator.validate(new Secret(null))).isEmpty();
    }

    @Test
    void countsBytesNotCharacters() {
        assertThat(validator.validate(new Secret("abcd"))).isEmpty();
        assertThat(validator.validate(new Secret("ññ"))).isEmpty();   // 4 bytes
        assertThat(validator.validate(new Secret("ñññ"))).hasSize(1); // 3 caracteres, 6 bytes
        assertThat(validator.validate(new Secret("a😀"))).hasSize(1); // 2 caracteres, 5 bytes
    }

    @Test
    void moreCharactersThanBytesIsRejectedWithoutEncoding() {
        Set<ConstraintViolation<Secret>> violations = validator.validate(new Secret("abcde"));

        assertThat(violations).singleElement()
                .extracting(ConstraintViolation::getMessage)
                .isEqualTo("no debe ocupar más de 4 bytes en UTF-8");
    }
}
