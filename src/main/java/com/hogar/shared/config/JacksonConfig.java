package com.hogar.shared.config;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Endurece la deserialización de entrada (D-045):
 * <ul>
 *   <li>Ningún texto puede contener U+0000: PostgreSQL no lo admite en {@code text/varchar} y
 *       acabaría como error de BD en vez de error de validación del campo.</li>
 *   <li>{@link LocalDate} solo se acepta como texto ISO-8601: un número ({@code 20260101}) no es
 *       un "epoch day" válido para la API.</li>
 * </ul>
 * Ambos casos producen {@code validation-error} con el campo en {@code errors[]}.
 */
@Configuration(proxyBeanMethods = false)
class JacksonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictInputCustomizer() {
        return builder -> builder
                .modulesToInstall(modules -> modules.add(
                        new SimpleModule("hogar-strict-strings").addDeserializer(String.class, new NulRejectingStringDeserializer())))
                .postConfigurer(mapper -> mapper.configOverride(LocalDate.class)
                        .setFormat(JsonFormat.Value.forLeniency(false)));
    }

    static final class NulRejectingStringDeserializer extends StringDeserializer {

        @Override
        public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String value = super.deserialize(p, ctxt);
            if (value != null && value.indexOf('\u0000') >= 0) {
                throw InvalidFormatException.from(p, "El texto contiene el carácter NUL", value, String.class);
            }
            return value;
        }
    }
}
