package com.hogar.shared.config;

/**
 * Textos reutilizables de la documentación OpenAPI (constantes para poder usarlos en anotaciones).
 *
 * <p>Convención de las respuestas de error: cada {@code code} posible va en su propia línea con el
 * formato {@code `codigo`: explicación}. {@link OpenApiConfig} añade a toda respuesta 4xx/5xx el
 * esquema {@code ProblemDetail} y genera un ejemplo por cada código que encuentra en la descripción,
 * así que basta con escribir la descripción.
 */
public final class ApiDoc {

    /** Separador de párrafos (Markdown) entre códigos de error de una misma respuesta. */
    public static final String NL = "\n\n";

    // ---- Tags (el orden de aparición en Swagger UI lo fija OpenApiConfig) ----

    public static final String TAG_AUTH = "Autenticación";
    public static final String TAG_USERS = "Usuarios";
    public static final String TAG_HOUSEHOLDS = "Hogares";
    public static final String TAG_MEMBERS = "Miembros";
    public static final String TAG_INVITATIONS = "Invitaciones";
    public static final String TAG_INCOMES = "Ingresos";
    public static final String TAG_EXPENSES = "Gastos";
    public static final String TAG_INVOICES = "Facturas";
    public static final String TAG_TASKS = "Tareas";
    public static final String TAG_BALANCES = "Balances";
    public static final String TAG_SETTLEMENTS = "Liquidaciones";

    // ---- Errores transversales ----

    public static final String VALIDATION_ERROR =
            "`validation-error`: algún campo no es válido (formato, rango o tipo); el detalle por campo va en `errors[]`.";
    public static final String MALFORMED_BODY =
            "`bad-request`: JSON sintácticamente roto, cuerpo ausente o UUID mal formado en la ruta.";
    public static final String BAD_PATH =
            "`bad-request`: UUID mal formado en la ruta.";
    public static final String BAD_PAGINATION =
            "`bad-request`: parámetro con formato incorrecto (UUID, fecha, enum), `page` fuera de "
                    + "0..21474836 o `size` fuera de 1..100 (no se acotan en silencio).";
    public static final String INVALID_DATE_RANGE =
            "`invalid-date-range`: `from` es posterior a `to`.";
    public static final String HOUSEHOLD_NOT_FOUND =
            "`household-not-found`: el hogar no existe o no eres miembro activo (no se distingue, para no "
                    + "revelar su existencia).";
    public static final String OWNER_ROLE_REQUIRED =
            "`owner-role-required`: la acción requiere el rol OWNER en el hogar.";
    public static final String CONCURRENT_MODIFICATION =
            "`concurrent-modification`: otra petición modificó el recurso a la vez; vuelve a cargarlo y reintenta.";
    public static final String DATA_CONFLICT =
            "`data-conflict`: la operación choca con el estado actual de los datos (carrera entre dos peticiones).";
    public static final String RATE_LIMITED =
            "`rate-limited`: demasiados intentos desde esta IP (o para este email); reintenta pasados los "
                    + "segundos de la cabecera `Retry-After`.";
    public static final String UNAUTHORIZED =
            "`unauthorized`: falta el access token o no es válido (caducado, firma, algoritmo o emisor incorrectos).";
    public static final String INTERNAL_ERROR =
            "`internal-error`: error inesperado del servidor (el detalle interno nunca se expone).";

    // ---- Recursos no encontrados (siempre 404, también si pertenecen a otro hogar) ----

    public static final String INCOME_NOT_FOUND = "`income-not-found`: el ingreso no existe en este hogar.";
    public static final String EXPENSE_NOT_FOUND = "`expense-not-found`: el gasto no existe en este hogar.";
    public static final String TASK_NOT_FOUND = "`task-not-found`: la tarea no existe en este hogar.";
    public static final String SETTLEMENT_NOT_FOUND =
            "`settlement-not-found`: la liquidación no existe en este hogar.";
    public static final String INVOICE_NOT_FOUND = "`invoice-not-found`: el gasto no tiene factura adjunta.";
    public static final String MEMBER_NOT_FOUND =
            "`member-not-found`: ese usuario no es miembro activo del hogar.";

    // ---- Límites del plan FREE (D-068) ----

    public static final String PLAN_HOUSEHOLD_LIMIT =
            "`plan-household-limit`: ya eres OWNER de tantos hogares FREE como permite el plan.";
    public static final String PLAN_MEMBER_LIMIT =
            "`plan-member-limit`: el hogar ya tiene el máximo de miembros de su plan.";
    public static final String PLAN_STORAGE_LIMIT =
            "`plan-storage-limit`: la factura haría superar el espacio de facturas del plan del hogar.";

    public static final String FREE_HISTORY_WINDOW =
            " En un hogar FREE solo se listan los de los últimos 12 meses naturales (el actual incluido), "
                    + "aunque `from` sea anterior; el detalle por ID y los balances usan todo el histórico (D-068).";

    // ---- Paginación ----

    public static final String PAGE = "Número de página, empezando en 0 (máximo 21474836).";
    public static final String SIZE = "Elementos por página, de 1 a 100.";

    private ApiDoc() {
    }
}
