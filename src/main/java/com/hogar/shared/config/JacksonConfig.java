package com.hogar.shared.config;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
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
 *   <li>{@link LocalDate} entre los años 1 y 9999: el driver JDBC lee mal las fechas a.C. que
 *       PostgreSQL sí guarda ({@code -0100-03-31} vuelve como {@code 0101-03-31}; {@code 0000-02-29}
 *       ni se puede leer) y los años de 5 cifras fallan al insertar (D-047).</li>
 * </ul>
 * Ambos casos producen {@code validation-error} con el campo en {@code errors[]}.
 */
@Configuration(proxyBeanMethods = false)
class JacksonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictInputCustomizer() {
        return builder -> builder
                .modulesToInstall(modules -> modules.add(new SimpleModule("hogar-strict-input")
                        .addDeserializer(String.class, new NulRejectingStringDeserializer())
                        .addDeserializer(LocalDate.class, new BoundedLocalDateDeserializer(LocalDateDeserializer.INSTANCE))))
                .postConfigurer(mapper -> mapper.configOverride(LocalDate.class)
                        .setFormat(JsonFormat.Value.forLeniency(false)));
    }

    /**
     * Delega en el deserializador estándar (contextualizado: respeta formato y {@code lenient=false})
     * y rechaza los años fuera de 1..9999.
     */
    static final class BoundedLocalDateDeserializer extends StdDeserializer<LocalDate>
            implements ContextualDeserializer {

        static final LocalDate MIN = LocalDate.of(1, 1, 1);
        static final LocalDate MAX = LocalDate.of(9999, 12, 31);

        private final JsonDeserializer<?> delegate;

        BoundedLocalDateDeserializer(JsonDeserializer<?> delegate) {
            super(LocalDate.class);
            this.delegate = delegate;
        }

        @Override
        public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property)
                throws JsonMappingException {
            JsonDeserializer<?> contextual = delegate instanceof ContextualDeserializer c
                    ? c.createContextual(ctxt, property)
                    : delegate;
            return new BoundedLocalDateDeserializer(contextual);
        }

        @Override
        public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            LocalDate value = (LocalDate) delegate.deserialize(p, ctxt);
            if (value != null && (value.isBefore(MIN) || value.isAfter(MAX))) {
                throw InvalidFormatException.from(p, "Fecha fuera del rango 0001-01-01..9999-12-31", value,
                        LocalDate.class);
            }
            return value;
        }
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
