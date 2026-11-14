package com.hogar.shared.config;

import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.NumericNode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

/**
 * Documento OpenAPI (springdoc). Las operaciones se describen con anotaciones en los controllers;
 * aquí va lo transversal para no repetirlo en cada endpoint:
 * <ul>
 *   <li>descripción general, esquema de seguridad bearer y orden de los tags;</li>
 *   <li>esquemas {@code ProblemDetail} y {@code FieldViolation};</li>
 *   <li>{@link #problemResponses()}: toda respuesta 4xx/5xx usa {@code application/problem+json} con
 *       ejemplos generados a partir de los códigos de su descripción, y se añaden 401 (rutas protegidas)
 *       y 500 a todas las operaciones.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    static final String BEARER = "bearerAuth";
    static final String PROBLEM_SCHEMA = "ProblemDetail";
    static final String FIELD_VIOLATION_SCHEMA = "FieldViolation";
    private static final String PROBLEM_JSON = org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;
    private static final String ALL = org.springframework.http.MediaType.ALL_VALUE;
    private static final String TYPE_BASE = "https://hogar.app/problems/";

    /** Una línea de la descripción de una respuesta de error: {@code `codigo`: explicación}. */
    private static final Pattern CODE_LINE = Pattern.compile("(?m)^`([a-z0-9-]+)`:\\s*(.+)$");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}]+)}");

    private static final String DESCRIPTION = """
            API REST para hogares compartidos (parejas, compañeros de piso): ingresos, gastos comunes con \
            reparto configurable (a partes iguales o proporcional a los ingresos), facturas adjuntas, tareas \
            de casa con rotación y liquidaciones ("quién debe a quién").

            **La aplicación no mueve dinero real**: solo registra lo que ocurre fuera de ella y calcula los \
            balances y los pagos sugeridos para saldarlos.

            ## Autenticación
            1. `POST /api/auth/register`, `/login` o `/google` devuelven un **access token** (JWT, vida corta) \
            y un **refresh token** opaco.
            2. Envía el access token en cada petición: `Authorization: Bearer <accessToken>` (botón \
            *Authorize* de esta página).
            3. Cuando caduque, `POST /api/auth/refresh` entrega un par nuevo; el refresh token es de **un solo \
            uso** (rotatorio): reutilizar uno ya usado revoca toda la sesión.

            Los endpoints de `/api/auth/**` son públicos y tienen rate limiting (429 con `Retry-After`).

            ## Hogares y permisos
            Todo lo que cuelga de `/api/households/{householdId}` exige ser **miembro activo** del hogar. Un \
            hogar ajeno, o un recurso de otro hogar, responde **404** (nunca 403) para no revelar que existe. \
            Algunas acciones exigen el rol `OWNER`.

            ## Errores (RFC 7807 / 9457)
            Todos los errores son `application/problem+json` con `type` estable \
            (`https://hogar.app/problems/<code>`), `title`, `status`, `detail`, `instance` y la propiedad \
            `code`, pensada para que el cliente decida qué hacer. Los errores de validación \
            (`validation-error`) incluyen `errors[]` con `field` y `message`. Errores comunes a toda la API:
            - `400 bad-request`: JSON roto, cuerpo ausente, parámetro o UUID de la ruta mal formado.
            - `401 unauthorized`: falta el token o no es válido.
            - `405`, `406`, `415`: método, `Accept` o `Content-Type` no admitidos.
            - `409 concurrent-modification`: dos ediciones simultáneas del mismo recurso; recarga y reintenta.
            - `409 data-conflict`: carrera entre peticiones detectada por la base de datos.
            - `500 internal-error`: error inesperado (sin detalles internos).

            ## Convenciones
            - **Importes**: decimales con **2 decimales** exactos (`"12.50"`), en la moneda del hogar \
            (ISO-4217, `EUR` por defecto). Se aceptan como número o como texto; más decimales → 400. Las \
            respuestas siempre llevan escala 2 (`0.00`, nunca `0`).
            - **Fechas** de negocio en ISO-8601 (`2026-03-15`); instantes en UTC (`2026-03-15T09:30:00Z`).
            - **IDs**: UUID.
            - **Paginación**: `page` (desde 0) y `size` (1–100, por defecto 20); el orden lo fija cada \
            listado. Valores fuera de rango → 400 (no se corrigen en silencio).
            - **Crear** devuelve `201` con cabecera `Location`.
            """;

    @Bean
    OpenAPI hogarOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Hogar API")
                        .version("0.1.0")
                        .description(DESCRIPTION)
                        .license(new License().name("Proyecto de portfolio")))
                .components(new Components()
                        .addSecuritySchemes(BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token obtenido en `/api/auth/*`. "
                                        + "Se envía como `Authorization: Bearer <token>`.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .tags(List.of(
                        tag(ApiDoc.TAG_AUTH, "Registro, login (email o Google), renovación y cierre de sesión. Públicos."),
                        tag(ApiDoc.TAG_USERS, "Perfil del usuario autenticado."),
                        tag(ApiDoc.TAG_HOUSEHOLDS, "Hogares del usuario: crear, consultar y configurar (política de reparto)."),
                        tag(ApiDoc.TAG_MEMBERS, "Miembros de un hogar: roles, expulsar y abandonar."),
                        tag(ApiDoc.TAG_INVITATIONS, "Códigos de invitación para unirse a un hogar."),
                        tag(ApiDoc.TAG_INCOMES, "Ingresos de cada miembro (base del reparto proporcional)."),
                        tag(ApiDoc.TAG_EXPENSES, "Gastos comunes y su reparto congelado entre los miembros."),
                        tag(ApiDoc.TAG_INVOICES, "Factura adjunta a un gasto (PDF, JPEG, PNG o WebP)."),
                        tag(ApiDoc.TAG_TASKS, "Tareas de casa, recurrencia, rotación de responsables e histórico."),
                        tag(ApiDoc.TAG_BALANCES, "Balances calculados y pagos sugeridos para saldar cuentas."),
                        tag(ApiDoc.TAG_SETTLEMENTS, "Liquidaciones: pagos entre miembros hechos fuera de la app.")));
    }

    /**
     * Respuestas de error comunes: 401 en operaciones protegidas, 500 en todas y, en cualquier 4xx/5xx,
     * cuerpo {@code ProblemDetail} con un ejemplo por cada código documentado en la descripción.
     */
    @Bean
    OpenApiCustomizer problemResponses() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            // Se registran aquí y no en el bean: springdoc elimina antes de los customizers los esquemas
            // a los que aún no apunta ninguna referencia.
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents()
                    .addSchemas(FIELD_VIOLATION_SCHEMA, fieldViolationSchema())
                    .addSchemas(PROBLEM_SCHEMA, problemSchema());
            Map<String, Schema> schemas = openApi.getComponents().getSchemas();
            schemas.values().forEach(OpenApiConfig::scaleMoneyExamples);
            openApi.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                ApiResponses responses = operation.getResponses();
                if (responses == null) {
                    responses = new ApiResponses();
                    operation.setResponses(responses);
                }
                boolean secured = operation.getSecurity() == null || !operation.getSecurity().isEmpty();
                if (secured && !responses.containsKey("401")) {
                    responses.addApiResponse("401", new ApiResponse().description(ApiDoc.UNAUTHORIZED));
                }
                if (!responses.containsKey("500")) {
                    responses.addApiResponse("500", new ApiResponse().description(ApiDoc.INTERNAL_ERROR));
                }
                String exampleField = firstBodyField(operation, schemas).orElse("name");
                responses.forEach((status, response) -> {
                    if (isError(status)) {
                        response.setContent(problemContent(Integer.parseInt(status), response.getDescription(),
                                path, exampleField));
                    } else {
                        jsonInsteadOfWildcard(response.getContent());
                    }
                });
                if (operation.getRequestBody() != null) {
                    jsonInsteadOfWildcard(operation.getRequestBody().getContent());
                }
            }));
            // Rutas en orden alfabético: el JSON publicado es estable y fácil de comparar entre versiones.
            Paths sorted = new Paths();
            new TreeMap<>(openApi.getPaths()).forEach(sorted::addPathItem);
            openApi.setPaths(sorted);
        };
    }

    /**
     * Sin {@code produces} explícito springdoc publica {@code *}{@code /*}; los cuerpos JSON de la API son
     * {@code application/json}.
     */
    private static void jsonInsteadOfWildcard(Content content) {
        if (content != null && content.containsKey(ALL)) {
            MediaType media = content.remove(ALL);
            content.addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE, media);
        }
    }

    /**
     * swagger-core convierte los ejemplos numéricos a {@code double} ({@code "1850.00"} → {@code 1850.0}). Los
     * importes (propiedades numéricas documentadas con "2 decimales") se reescriben con escala 2, como los
     * devuelve la API.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void scaleMoneyExamples(Schema<?> schema) {
        if (schema.getProperties() == null) {
            return;
        }
        for (Schema property : schema.getProperties().values()) {
            boolean number = "number".equals(property.getType())
                    || (property.getTypes() != null && property.getTypes().contains("number"));
            if (!number || property.getDescription() == null || !property.getDescription().contains("2 decimales")) {
                continue;
            }
            // Con OpenAPI 3.1 el ejemplo llega como nodo JSON; con 3.0, como Number.
            Object example = property.getExample();
            BigDecimal value = example instanceof NumericNode node ? node.decimalValue()
                    : example instanceof Number n ? new BigDecimal(n.toString()) : null;
            if (value != null) {
                property.setExample(DecimalNode.valueOf(value.setScale(2, RoundingMode.HALF_EVEN)));
            }
        }
    }

    private static boolean isError(String status) {
        return status.length() == 3 && (status.startsWith("4") || status.startsWith("5"))
                && status.chars().allMatch(Character::isDigit);
    }

    private static Content problemContent(int status, String description, String path, String exampleField) {
        MediaType mediaType = new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA));
        Matcher matcher = CODE_LINE.matcher(description == null ? "" : description);
        Map<String, Example> examples = new LinkedHashMap<>();
        while (matcher.find()) {
            String code = matcher.group(1);
            examples.putIfAbsent(code, new Example()
                    .summary(code)
                    .value(problemExample(status, code, matcher.group(2), path, exampleField)));
        }
        if (!examples.isEmpty()) {
            mediaType.setExamples(examples);
        }
        return new Content().addMediaType(PROBLEM_JSON, mediaType);
    }

    private static Map<String, Object> problemExample(int status, String code, String explanation, String path,
                                                      String exampleField) {
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("type", TYPE_BASE + code);
        HttpStatus httpStatus = HttpStatus.resolve(status);
        example.put("title", httpStatus != null ? httpStatus.getReasonPhrase() : "Error");
        example.put("status", status);
        example.put("detail", sentence(explanation));
        example.put("instance", samplePath(path));
        example.put("code", code);
        example.put("requestId", "6f1c2b9e-0d4a-4c47-9a51-3c2e7d8b1f20");
        if ("validation-error".equals(code)) {
            Map<String, Object> violation = new LinkedHashMap<>();
            violation.put("field", exampleField);
            violation.put("message", "no debe ser nulo");
            example.put("errors", List.of(violation));
        }
        return example;
    }

    /** Primera letra en mayúscula y sin el Markdown de la descripción: así parece un {@code detail} real. */
    private static String sentence(String explanation) {
        String plain = explanation.replace("`", "").trim();
        return plain.isEmpty() ? plain : Character.toUpperCase(plain.charAt(0)) + plain.substring(1);
    }

    /** Ruta de ejemplo para {@code instance}: sustituye las variables por valores verosímiles. */
    private static String samplePath(String path) {
        Matcher matcher = PATH_VARIABLE.matcher(path);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String sample = switch (matcher.group(1)) {
                case "householdId" -> "0b8e6c62-4a5e-4c1f-9d3a-7f2f1e9a4c10";
                case "code" -> "K7M2Q9XW4P";
                default -> "a3c1f7d2-5b6e-4f80-8c9d-1e2f3a4b5c6d";
            };
            matcher.appendReplacement(sb, sample);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** Primer campo obligatorio (o el primero) del cuerpo JSON de la operación, para el ejemplo de {@code errors[]}. */
    @SuppressWarnings("rawtypes")
    private static Optional<String> firstBodyField(Operation operation, Map<String, Schema> schemas) {
        if (operation.getRequestBody() == null || operation.getRequestBody().getContent() == null) {
            return Optional.empty();
        }
        MediaType json = operation.getRequestBody().getContent().get(org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
        if (json == null || json.getSchema() == null || json.getSchema().get$ref() == null) {
            return Optional.empty();
        }
        String ref = json.getSchema().get$ref();
        Schema<?> body = schemas.get(ref.substring(ref.lastIndexOf('/') + 1));
        if (body == null) {
            return Optional.empty();
        }
        if (body.getRequired() != null && !body.getRequired().isEmpty()) {
            return Optional.of(body.getRequired().get(0));
        }
        return body.getProperties() == null ? Optional.empty() : body.getProperties().keySet().stream().findFirst();
    }

    private static Tag tag(String name, String description) {
        return new Tag().name(name).description(description);
    }

    private static Schema<?> fieldViolationSchema() {
        return new ObjectSchema()
                .description("Error de validación de un campo.")
                .addProperty("field", new StringSchema()
                        .description("Campo afectado; ruta con puntos e índices si está anidado (`rotation[1]`).")
                        .example("amount"))
                .addProperty("message", new StringSchema()
                        .description("Motivo legible.")
                        .example("debe ser mayor que o igual a 0.01"))
                .required(List.of("field", "message"));
    }

    private static Schema<?> problemSchema() {
        return new ObjectSchema()
                .description("Error en formato RFC 7807 / 9457 (`application/problem+json`).")
                .addProperty("type", new StringSchema().format("uri")
                        .description("Identificador estable del tipo de error: `https://hogar.app/problems/<code>`.")
                        .example(TYPE_BASE + "household-not-found"))
                .addProperty("title", new StringSchema()
                        .description("Resumen del estado HTTP.")
                        .example("Not Found"))
                .addProperty("status", new IntegerSchema().format("int32")
                        .description("Código de estado HTTP.")
                        .example(404))
                .addProperty("detail", new StringSchema()
                        .description("Explicación legible para personas (no la analices: usa `code`).")
                        .example("Hogar no encontrado."))
                .addProperty("instance", new StringSchema().format("uri-reference")
                        .description("Ruta de la petición que falló.")
                        .example("/api/households/0b8e6c62-4a5e-4c1f-9d3a-7f2f1e9a4c10"))
                .addProperty("code", new StringSchema()
                        .description("Código estable del error, en kebab-case. Es lo que debe interpretar el cliente.")
                        .example("household-not-found"))
                .addProperty("requestId", new StringSchema()
                        .description("Identificador de la petición (el mismo de la cabecera `X-Request-Id`), "
                                + "útil para buscarla en los logs.")
                        .example("6f1c2b9e-0d4a-4c47-9a51-3c2e7d8b1f20"))
                .addProperty("errors", new ArraySchema()
                        .items(new Schema<>().$ref("#/components/schemas/" + FIELD_VIOLATION_SCHEMA))
                        .description("Solo en `validation-error`: un elemento por campo no válido."))
                .required(List.of("type", "title", "status", "code"));
    }
}
