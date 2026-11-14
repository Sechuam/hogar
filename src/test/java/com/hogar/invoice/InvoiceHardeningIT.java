package com.hogar.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.hogar.expense.FinanceApiSupport;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.invoice.storage.LocalFileSystemStorage;
import com.hogar.invoice.storage.LocalStorageProperties;
import com.hogar.invoice.storage.StorageService;
import com.hogar.support.TestUsers.TestUser;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Facturas frente a clientes hostiles y carreras, con la configuración real de límites (10 MB de
 * multipart y de {@code hogar.invoices.max-size}). Las peticiones van con {@link HttpClient} y cuerpos
 * multipart escritos a mano para controlar cada byte y cabecera.
 *
 * <p>El almacenamiento es el real ({@link LocalFileSystemStorage}) en un directorio propio, envuelto en
 * {@link GatedStorage}: cuenta los streams abiertos/cerrados y permite detener una petición en un punto
 * exacto (tras escribir el fichero, antes de abrirlo o antes de leerlo) para provocar carreras de forma
 * determinista.
 */
@Import(InvoiceHardeningIT.GatedStorageConfig.class)
class InvoiceHardeningIT extends FinanceApiSupport {

    private static final int TEN_MB = 10 * 1024 * 1024;
    private static final LocalDate MARCH = LocalDate.of(2026, 3, 10);
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    @TempDir
    static Path storageDir;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("hogar.storage.local.base-dir", () -> storageDir.toString());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private GatedStorage storage;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(WAIT).build();

    @AfterAll
    static void shutdownPool() {
        POOL.shutdownNow();
    }

    /** Hogar de Ana (OWNER) con Luis, Eva y Pablo; gasto creado por Luis y pagado por Eva. */
    private record Flat(UUID id, TestUser ana, TestUser luis, TestUser eva, TestUser pablo, UUID expenseId) {

        String invoice() {
            return expensePath(id, expenseId) + "/invoice";
        }
    }

    private Flat flat() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser eva = users().create("Eva");
        TestUser pablo = users().create("Pablo");
        UUID id = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, id, luis, eva, pablo);
        UUID expenseId = createExpense(luis, id, expense("40.00", MARCH, eva.id())).id();
        return new Flat(id, ana, luis, eva, pablo, expenseId);
    }

    // --- Descarga: nunca un tipo ejecutable, siempre como adjunto, y el fichero siempre se cierra ---

    /**
     * El {@code Content-Type} de la respuesta se fija antes de la negociación, así que el {@code Accept}
     * del cliente no la hace fallar (no hay 406 con el fichero ya abierto) ni cambia el tipo servido. En
     * todos los casos el stream se cierra: la fuga sospechada al fallar la negociación no se reproduce.
     */
    @ParameterizedTest(name = "Accept: {0}")
    @ValueSource(strings = {"application/json", "text/html", "image/svg+xml", "application/xml", "foo",
            "application/pdf;q=0, */*;q=0"})
    void anyAcceptHeaderGetsThePdfAsAttachmentAndTheFileIsClosed(String accept) throws Exception {
        Flat f = flat();
        byte[] polyglot = polyglotPdf();
        assertCreated(upload(f.luis(), f.invoice(), multipart(part("file", "filename=\"f.pdf\"", "text/html", polyglot))));
        int opened = storage.opened.get();
        int closed = storage.closed.get();

        HttpResponse<byte[]> response = send(request(f.pablo(), f.invoice()).header("Accept", accept).GET());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(polyglot);
        assertSafeDownloadHeaders(response, "application/pdf");
        awaitClosed(opened, closed);
    }

    /** HEAD y {@code Range}: mismo tratamiento, sin respuestas parciales con un recurso de un solo uso. */
    @Test
    void headAndRangeRequestsAreServedWholeAndCloseTheFile() throws Exception {
        Flat f = flat();
        byte[] pdf = pdf();
        assertCreated(upload(f.luis(), f.invoice(), file("f.pdf", pdf)));
        int opened = storage.opened.get();
        int closed = storage.closed.get();

        HttpResponse<byte[]> head = send(request(f.ana(), f.invoice()).method("HEAD",
                HttpRequest.BodyPublishers.noBody()));
        HttpResponse<byte[]> range = send(request(f.ana(), f.invoice()).header("Range", "bytes=0-3").GET());
        HttpResponse<byte[]> badRange = send(request(f.ana(), f.invoice()).header("Range", "bytes=999999-").GET());

        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertSafeDownloadHeaders(head, "application/pdf");
        for (HttpResponse<byte[]> full : List.of(range, badRange)) {
            assertThat(full.statusCode()).isEqualTo(200);
            assertThat(full.body()).isEqualTo(pdf);
            assertThat(full.headers().firstValue("Content-Range")).isEmpty();
            assertSafeDownloadHeaders(full, "application/pdf");
        }
        assertThat(storage.opened.get() - opened).isEqualTo(3);
        awaitClosed(opened, closed);
    }

    /**
     * Polyglot: empieza por {@code %PDF-} y lleva HTML con script detrás. Se acepta (los magic bytes son
     * de PDF), pero solo se sirve como {@code application/pdf} + {@code nosniff} + adjunto, nunca como
     * HTML, digan lo que digan el {@code Content-Type} de la parte y la extensión.
     */
    @Test
    void polyglotPdfWithHtmlIsOnlyEverServedAsPdfAttachment() throws Exception {
        Flat f = flat();

        JsonNode created = assertCreated(upload(f.luis(), f.invoice(),
                multipart(part("file", "filename=\"pagina.html\"", "text/html", polyglotPdf()))));

        assertThat(created.path("contentType").asText()).isEqualTo("application/pdf");
        assertThat(created.path("filename").asText()).isEqualTo("pagina.html");
        HttpResponse<byte[]> download = send(request(f.ana(), f.invoice()).header("Accept", "text/html").GET());
        assertSafeDownloadHeaders(download, "application/pdf");
        assertThat(ContentDisposition.parse(download.headers().firstValue("Content-Disposition").orElseThrow())
                .isAttachment()).isTrue();
    }

    /** Contenido activo o con la firma desplazada: 415 y nada en disco. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "<html><script>alert(1)</script></html>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>",
            "<?xml version=\"1.0\"?><x/>",
            "\uFEFF%PDF-1.4 con BOM delante",
            " %PDF-1.4 con un espacio delante",
            "%PDF",
            "RIFF\u0000\u0000\u0000\u0000WEBX",
            "GIF89a........"})
    void activeOrShiftedContentIsUnsupportedAndLeavesNothing(String content) throws Exception {
        Flat f = flat();
        List<String> before = filesOnDisk();

        HttpResponse<byte[]> response = upload(f.luis(), f.invoice(),
                multipart(part("file", "filename=\"factura.pdf\"", "application/pdf",
                        content.getBytes(StandardCharsets.UTF_8))));

        assertProblem(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-file-type");
        assertNoInvoice(f);
        assertThat(filesOnDisk()).containsExactlyInAnyOrderElementsOf(before);
    }

    // --- Nombres hostiles en Content-Disposition ---

    /**
     * El nombre (ya saneado) viaja en {@code filename} (RFC 2047) y {@code filename*} (RFC 5987): ni un
     * {@code ;} ni un {@code filename=} incrustado pueden cambiar el nombre que interpreta el navegador,
     * ni CR/LF partir la cabecera, ni U+202E disfrazar la extensión.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "a.pdf; filename=evil.html",
            "a.pdf\"; x=\"evil.html",
            "a.pdf\r\nSet-Cookie: x=1",
            "factura\u202Elmth.pdf",
            "tab\tand\u007Fdel.pdf",
            "..\\..\\windows\\win.ini",
            "%2e%2e%2fetc%2fpasswd"})
    void hostileFilenameNeverBreaksOrHijacksContentDisposition(String hostile) throws Exception {
        Flat f = flat();
        String encoded = URLEncoder.encode(hostile, StandardCharsets.UTF_8).replace("+", "%20");

        JsonNode created = assertCreated(upload(f.luis(), f.invoice(),
                multipart(part("file", "filename*=UTF-8''" + encoded, "application/pdf", pdf()))));
        String stored = created.path("filename").asText();

        assertThat(stored).doesNotContain("\r", "\n", "\"", "/", "\\", "\u202E", "\t", "\u007F");
        HttpResponse<byte[]> download = send(request(f.ana(), f.invoice()).GET());
        assertThat(download.statusCode()).isEqualTo(200);
        List<String> dispositions = download.headers().allValues("Content-Disposition");
        assertThat(dispositions).hasSize(1);
        assertThat(download.headers().firstValue("Set-Cookie")).isEmpty();
        ContentDisposition disposition = ContentDisposition.parse(dispositions.get(0));
        assertThat(disposition.isAttachment()).isTrue();
        assertThat(disposition.getFilename()).isEqualTo(stored);
        assertThat(storageKeyOf(f.expenseId())).satisfies(UUID::fromString);
        assertThat(filesOnDisk()).doesNotContain(stored);
    }

    /** Más de 255 caracteres ASCII: se recorta a 255 y la descarga sigue siendo válida. */
    @Test
    void overlongAsciiFilenameIsTruncatedTo255() throws Exception {
        Flat f = flat();

        JsonNode created = assertCreated(upload(f.luis(), f.invoice(), file("x".repeat(300) + ".pdf", pdf())));

        assertThat(created.path("filename").asText()).isEqualTo("x".repeat(255));
        HttpResponse<byte[]> download = send(request(f.ana(), f.invoice()).GET());
        assertThat(ContentDisposition.parse(download.headers().firstValue("Content-Disposition").orElseThrow())
                .getFilename()).isEqualTo("x".repeat(255));
    }

    /**
     * Tomcat limita cada cabecera de parte multipart a 512 bytes ({@code server.tomcat.max-part-header-size})
     * y Spring traduce el rechazo a {@code MaxUploadSizeExceededException}: un PDF de pocos bytes con un
     * nombre largo no ASCII (aquí 60 emojis, 720 bytes en {@code filename*}) recibe 413 "fichero demasiado
     * grande", que es falso y confunde al cliente.
     */
    @Test
    @Disabled("BUG: nombre largo no ASCII → 413 file-too-large por el límite de 512 B de cabecera de parte de "
            + "Tomcat; fix propuesto: server.tomcat.max-part-header-size: 4KB en application.yml")
    void longNonAsciiFilenameOfASmallFileIsAccepted() throws Exception {
        Flat f = flat();
        String name = "😀".repeat(60) + ".pdf";

        HttpResponse<byte[]> response = upload(f.luis(), f.invoice(), multipart(part("file",
                "filename*=UTF-8''" + URLEncoder.encode(name, StandardCharsets.UTF_8), "application/pdf", pdf())));

        assertCreated(response);
    }

    // --- Multipart mal formado o inesperado ---

    /** La primera parte {@code file} es la que cuenta; la segunda se ignora y no deja nada en disco. */
    @Test
    void repeatedFilePartStoresOnlyTheFirst() throws Exception {
        Flat f = flat();
        List<String> before = filesOnDisk();
        byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};

        JsonNode created = assertCreated(upload(f.luis(), f.invoice(), multipart(
                part("file", "filename=\"primera.pdf\"", "application/pdf", pdf()),
                part("file", "filename=\"segunda.exe\"", "application/pdf", exe))));

        assertThat(created.path("filename").asText()).isEqualTo("primera.pdf");
        assertThat(filesOnDisk()).hasSize(before.size() + 1).contains(storageKeyOf(f.expenseId()));
    }

    /** Parte {@code file} sin {@code filename} (campo de texto, no fichero) o cuerpo no multipart: 4xx y nada en disco. */
    @Test
    void filePartWithoutFilenameOrNonMultipartBodyIsRejected() throws Exception {
        Flat f = flat();
        List<String> before = filesOnDisk();

        HttpResponse<byte[]> textField = upload(f.luis(), f.invoice(),
                multipart(part("file", null, null, pdf())));
        HttpResponse<byte[]> rawPdf = send(request(f.luis(), f.invoice()).header("Content-Type", "application/pdf")
                .POST(HttpRequest.BodyPublishers.ofByteArray(pdf())));

        assertThat(textField.statusCode()).as(new String(textField.body(), StandardCharsets.UTF_8)).isEqualTo(400);
        assertProblem(rawPdf, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type");
        assertNoInvoice(f);
        assertThat(filesOnDisk()).containsExactlyInAnyOrderElementsOf(before);
    }

    /**
     * Multipart que Tomcat no consigue analizar: truncado, sin boundary, o con un {@code filename*} que
     * su parser rechaza ({@code %00}, o un {@code *} sin codificar dentro del valor). Nunca llegan al
     * saneador de nombres.
     */
    static Stream<Arguments> malformedMultipart() {
        String head = "--b\r\nContent-Disposition: form-data; name=\"file\"; ";
        return Stream.of(
                Arguments.of("truncado", "multipart/form-data; boundary=b",
                        head + "filename=\"a.pdf\"\r\n\r\n%PDF-1"),
                Arguments.of("sin boundary", "multipart/form-data", "%PDF-1.4"),
                Arguments.of("filename* con %00", "multipart/form-data; boundary=b",
                        head + "filename*=UTF-8''nul%00byte.pdf\r\n\r\n%PDF-1.4\r\n--b--\r\n"),
                Arguments.of("filename* con * literal", "multipart/form-data; boundary=b",
                        head + "filename*=UTF-8''a%22%3B%20filename*%3DUTF-8%27%27evil.html\r\n\r\n%PDF-1.4\r\n--b--\r\n"));
    }

    /**
     * Un multipart que no se puede analizar es un error del cliente, pero hoy acaba en
     * {@code GlobalExceptionHandler.handleUnexpected}: 500 {@code internal-error} y una traza ERROR en
     * el log por cada petición (ruido que cualquier usuario autenticado puede provocar a voluntad).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedMultipart")
    @Disabled("BUG: multipart mal formado → 500 internal-error; fix propuesto en shared/error/"
            + "GlobalExceptionHandler: @ExceptionHandler(MultipartException) → 400 malformed-multipart")
    void malformedMultipartIsBadRequest(String name, String contentType, String body) throws Exception {
        Flat f = flat();

        HttpResponse<byte[]> response = send(request(f.luis(), f.invoice()).header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8))));

        assertThat(response.statusCode()).isEqualTo(400);
    }

    /** Lo que sí se cumple hoy con multipart roto: la petición no llega al servicio y no deja fichero ni fila. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedMultipart")
    void malformedMultipartNeverReachesTheServiceNorLeavesAnything(String name, String contentType, String body)
            throws Exception {
        Flat f = flat();
        List<String> before = filesOnDisk();

        HttpResponse<byte[]> response = send(request(f.luis(), f.invoice()).header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8))));

        assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
        assertNoInvoice(f);
        assertThat(filesOnDisk()).containsExactlyInAnyOrderElementsOf(before);
    }

    // --- Límites exactos con la configuración real (10 MB) ---

    @Test
    void fileOfExactlyTenMegabytesIsAcceptedAndOneMoreByteIsNot() throws Exception {
        Flat f = flat();
        Flat g = flat();
        byte[] max = new byte[TEN_MB];
        byte[] header = pdf();
        System.arraycopy(header, 0, max, 0, header.length);
        byte[] tooBig = Arrays.copyOf(max, TEN_MB + 1);

        JsonNode created = assertCreated(upload(f.luis(), f.invoice(), file("max.pdf", max)));
        assertThat(created.path("sizeBytes").asLong()).isEqualTo(TEN_MB);
        assertThat(Files.size(storageDir.resolve(storageKeyOf(f.expenseId())))).isEqualTo(TEN_MB);

        List<String> before = filesOnDisk();
        assertProblem(upload(g.luis(), g.invoice(), file("big.pdf", tooBig)), HttpStatus.PAYLOAD_TOO_LARGE,
                "file-too-large");
        assertNoInvoice(g);
        assertThat(filesOnDisk()).containsExactlyInAnyOrderElementsOf(before);
    }

    // --- Rutas: ninguna entrada del cliente llega al disco y nada da 500 ---

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"not-a-uuid", "..%2F..%2Fetc", "%2e%2e", "00000000-0000-0000-0000-00000000000g"})
    void hostileExpenseIdInThePathIsRejectedWithoutServerError(String expenseId) throws Exception {
        Flat f = flat();
        String path = expensesPath(f.id()) + "/" + expenseId + "/invoice";

        HttpResponse<byte[]> get = send(request(f.ana(), path).GET());
        HttpResponse<byte[]> post = upload(f.ana(), path, file("f.pdf", pdf()));

        assertThat(get.statusCode()).isBetween(400, 404);
        assertThat(post.statusCode()).isBetween(400, 404);
    }

    // --- Concurrencia (determinista: una petición se detiene en un punto exacto) ---

    /**
     * Dos subidas al mismo gasto: la primera se detiene tras escribir su fichero, con la fila del gasto
     * bloqueada; la segunda queda esperando ese bloqueo. Al soltar la primera: 201 y 409, sin 500, un
     * único fichero y una única fila.
     */
    @Test
    void concurrentUploadsToTheSameExpenseAreOneCreatedAndOneConflict() throws Exception {
        Flat f = flat();
        List<String> before = filesOnDisk();
        Gate gate = storage.armStore();

        Future<HttpResponse<byte[]>> first = POOL.submit(() -> upload(f.luis(), f.invoice(), file("a.pdf", pdf())));
        gate.awaitEntered();
        Future<HttpResponse<byte[]>> second = POOL.submit(() -> upload(f.ana(), f.invoice(), file("b.pdf", pdf())));
        awaitBlockedOnLock();
        gate.release();

        JsonNode created = assertCreated(first.get(WAIT.toSeconds(), TimeUnit.SECONDS));
        assertProblem(second.get(WAIT.toSeconds(), TimeUnit.SECONDS), HttpStatus.CONFLICT, "invoice-already-exists");
        assertThat(created.path("filename").asText()).isEqualTo("a.pdf");
        assertThat(invoiceRows(f.expenseId())).isEqualTo(1);
        assertThat(filesOnDisk()).hasSize(before.size() + 1).contains(storageKeyOf(f.expenseId()));
    }

    /**
     * Borrado del gasto mientras se sube su factura: el borrado espera al bloqueo de la subida, ve la
     * factura recién confirmada y borra su fichero tras el commit. No queda fila ni fichero.
     */
    @Test
    void expenseDeletedDuringUploadRemovesTheJustUploadedFile() throws Exception {
        Flat f = flat();
        List<String> before = filesOnDisk();
        Gate gate = storage.armStore();

        Future<HttpResponse<byte[]>> upload = POOL.submit(() -> upload(f.luis(), f.invoice(), file("a.pdf", pdf())));
        gate.awaitEntered();
        Future<HttpResponse<byte[]>> delete = POOL.submit(() ->
                send(request(f.luis(), expensePath(f.id(), f.expenseId())).DELETE()));
        awaitBlockedOnLock();
        gate.release();

        assertCreated(upload.get(WAIT.toSeconds(), TimeUnit.SECONDS));
        assertThat(delete.get(WAIT.toSeconds(), TimeUnit.SECONDS).statusCode()).isEqualTo(204);
        assertThat(invoiceRows(f.expenseId())).isZero();
        assertThat(filesOnDisk()).containsExactlyInAnyOrderElementsOf(before);
        assertProblem(upload(f.luis(), f.invoice(), file("tarde.pdf", pdf())), HttpStatus.NOT_FOUND,
                "expense-not-found");
        assertThat(filesOnDisk()).containsExactlyInAnyOrderElementsOf(before);
    }

    /**
     * La factura se borra (y su fichero, tras el commit) cuando una descarga ya leyó los metadatos pero
     * aún no abrió el fichero: la descarga responde 404 {@code invoice-not-found}, no 500.
     */
    @Test
    void invoiceDeletedBeforeTheDownloadOpensTheFileIsNotFound() throws Exception {
        Flat f = flat();
        assertCreated(upload(f.luis(), f.invoice(), file("a.pdf", pdf())));
        String key = storageKeyOf(f.expenseId());
        Gate gate = storage.armOpen();

        Future<HttpResponse<byte[]>> download = POOL.submit(() -> send(request(f.pablo(), f.invoice()).GET()));
        gate.awaitEntered();
        assertThat(send(request(f.eva(), f.invoice()).DELETE()).statusCode()).isEqualTo(204);
        assertThat(storageDir.resolve(key)).doesNotExist();
        gate.release();

        assertProblem(download.get(WAIT.toSeconds(), TimeUnit.SECONDS), HttpStatus.NOT_FOUND, "invoice-not-found");
    }

    /** Si el fichero ya estaba abierto, la descarga termina entera aunque se borre a mitad (POSIX). */
    @Test
    void invoiceDeletedWhileDownloadingStillDeliversTheWholeFile() throws Exception {
        Flat f = flat();
        byte[] pdf = pdf();
        assertCreated(upload(f.luis(), f.invoice(), file("a.pdf", pdf)));
        String key = storageKeyOf(f.expenseId());
        int opened = storage.opened.get();
        int closed = storage.closed.get();
        Gate gate = storage.armRead();

        Future<HttpResponse<byte[]>> download = POOL.submit(() -> send(request(f.pablo(), f.invoice()).GET()));
        gate.awaitEntered();
        assertThat(send(request(f.eva(), f.invoice()).DELETE()).statusCode()).isEqualTo(204);
        assertThat(storageDir.resolve(key)).doesNotExist();
        gate.release();

        HttpResponse<byte[]> response = download.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(pdf);
        awaitClosed(opened, closed);
    }

    // --- Consistencia BD ↔ disco cuando falla el propio commit ---

    /**
     * PostgreSQL rechaza la transacción en el COMMIT (trigger de restricción diferido). Spring la da por
     * deshecha ({@code STATUS_ROLLED_BACK}) y el fichero se borra: ni fila ni huérfano. Si el resultado
     * fuera desconocido ({@code STATUS_UNKNOWN}: conexión perdida durante el commit) el fichero se
     * conservaría a propósito (ver {@code InvoiceServiceTest.unknownOutcomeKeepsTheFileBecauseTheRowMayExist}).
     */
    @Test
    void commitRejectedByTheDatabaseLeavesNeitherRowNorFile() throws Exception {
        Flat f = flat();
        String poison = "commit-" + UUID.randomUUID() + ".pdf";
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "it_fail_commit_" + suffix;
        String trigger = "it_fail_commit_trg_" + suffix;
        jdbc.execute("create function " + function + "() returns trigger language plpgsql as $$ "
                + "begin raise exception 'commit rechazado'; end $$");
        jdbc.execute("create constraint trigger " + trigger + " after insert on invoices deferrable initially deferred "
                + "for each row when (new.filename = '" + poison + "') execute function " + function + "()");
        List<String> before = filesOnDisk();
        HttpResponse<byte[]> response;
        try {
            response = upload(f.luis(), f.invoice(), file(poison, pdf()));
        } finally {
            jdbc.execute("drop trigger " + trigger + " on invoices");
            jdbc.execute("drop function " + function + "()");
        }

        assertProblem(response, HttpStatus.INTERNAL_SERVER_ERROR, "internal-error");
        assertNoInvoice(f);
        assertThat(filesOnDisk()).containsExactlyInAnyOrderElementsOf(before);
    }

    // --- Ex-miembros ---

    /**
     * Eva (pagadora) se va del hogar y Luis (creador) es expulsado: pierden todo acceso (404, sin
     * filtrar que la factura existe). Ana, OWNER sin ser pagadora ni creadora, sigue pudiendo borrarla
     * y subir otra; Pablo, miembro sin permiso sobre el gasto, solo descargarla.
     */
    @Test
    void payerWhoLeftAndExpelledCreatorLoseAccessWhileTheOwnerKeepsIt() throws Exception {
        Flat f = flat();
        assertCreated(upload(f.eva(), f.invoice(), file("luz.pdf", pdf())));
        assertThat(call(org.springframework.http.HttpMethod.POST, householdPath(f.id()) + "/leave", f.eva(), null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(org.springframework.http.HttpMethod.DELETE, householdPath(f.id()) + "/members/" + f.luis().id(),
                f.ana(), null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        for (TestUser former : List.of(f.eva(), f.luis())) {
            assertProblem(send(request(former, f.invoice()).GET()), HttpStatus.NOT_FOUND, "household-not-found");
            assertProblem(send(request(former, f.invoice() + "/metadata").GET()), HttpStatus.NOT_FOUND,
                    "household-not-found");
            assertProblem(send(request(former, f.invoice()).DELETE()), HttpStatus.NOT_FOUND, "household-not-found");
            assertProblem(upload(former, f.invoice(), file("x.pdf", pdf())), HttpStatus.NOT_FOUND,
                    "household-not-found");
        }
        assertThat(send(request(f.pablo(), f.invoice()).GET()).statusCode()).isEqualTo(200);
        assertProblem(send(request(f.pablo(), f.invoice()).DELETE()), HttpStatus.FORBIDDEN, "expense-edit-forbidden");

        List<String> before = filesOnDisk();
        assertThat(send(request(f.ana(), f.invoice()).DELETE()).statusCode()).isEqualTo(204);
        assertThat(filesOnDisk()).hasSize(before.size() - 1);
        assertCreated(upload(f.ana(), f.invoice(), file("nueva.pdf", pdf())));
    }

    // --- Utilidades HTTP ---

    private HttpRequest.Builder request(TestUser user, String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(WAIT)
                .header("Authorization", "Bearer " + user.accessToken());
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> upload(TestUser user, String path, Multipart body)
            throws IOException, InterruptedException {
        return send(request(user, path).header("Content-Type", "multipart/form-data; boundary=" + body.boundary())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.bytes())));
    }

    private record Multipart(String boundary, byte[] bytes) {
    }

    private static final String BOUNDARY = "hogar-it-boundary";

    /** Parte multipart; {@code filenameParam} y {@code contentType} nulos se omiten. */
    private static byte[] part(String name, String filenameParam, String contentType, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String disposition = "Content-Disposition: form-data; name=\"" + name + "\""
                + (filenameParam != null ? "; " + filenameParam : "") + "\r\n";
        out.writeBytes(("--" + BOUNDARY + "\r\n" + disposition
                + (contentType != null ? "Content-Type: " + contentType + "\r\n" : "") + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static Multipart multipart(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        out.writeBytes(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return new Multipart(BOUNDARY, out.toByteArray());
    }

    private static Multipart file(String filename, byte[] content) {
        return multipart(part("file", "filename=\"" + filename + "\"", "application/octet-stream", content));
    }

    private JsonNode assertCreated(HttpResponse<byte[]> response) throws IOException {
        String body = new String(response.body(), StandardCharsets.UTF_8);
        assertThat(response.statusCode()).as(body).isEqualTo(201);
        return json.readTree(body);
    }

    private void assertProblem(HttpResponse<byte[]> response, HttpStatus status, String code) throws IOException {
        String body = new String(response.body(), StandardCharsets.UTF_8);
        assertThat(response.statusCode()).as(body).isEqualTo(status.value());
        assertThat(json.readTree(body).path("code").asText()).as(body).isEqualTo(code);
    }

    private static void assertSafeDownloadHeaders(HttpResponse<byte[]> response, String contentType) {
        var headers = response.headers();
        assertThat(headers.allValues("Content-Type")).containsExactly(contentType);
        assertThat(headers.allValues("X-Content-Type-Options")).containsExactly("nosniff");
        assertThat(headers.firstValue("Cache-Control")).hasValue("private, no-store");
        assertThat(headers.firstValue("Content-Disposition")).hasValueSatisfying(d ->
                assertThat(d).startsWith("attachment;"));
    }

    private void assertNoInvoice(Flat f) throws IOException, InterruptedException {
        assertProblem(send(request(f.ana(), f.invoice() + "/metadata").GET()), HttpStatus.NOT_FOUND,
                "invoice-not-found");
        assertThat(invoiceRows(f.expenseId())).isZero();
    }

    /** Los streams abiertos desde {@code opened} se cierran (el cierre puede llegar justo tras la respuesta). */
    private void awaitClosed(int openedBefore, int closedBefore) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        int expected = storage.opened.get() - openedBefore;
        while (storage.closed.get() - closedBefore < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(storage.closed.get() - closedBefore).as("streams de factura cerrados").isEqualTo(expected);
    }

    /** Espera a que alguna sesión de la BD esté bloqueada esperando un lock (la petición concurrente). */
    private void awaitBlockedOnLock() throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity "
                    + "where datname = current_database() and wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("La petición concurrente no llegó a esperar el bloqueo del gasto");
    }

    private int invoiceRows(UUID expenseId) {
        return jdbc.queryForObject("select count(*) from invoices where expense_id = ?", Integer.class, expenseId);
    }

    private String storageKeyOf(UUID expenseId) {
        return jdbc.queryForObject("select storage_key from invoices where expense_id = ?", String.class, expenseId);
    }

    /** Ficheros del almacenamiento, temporales incluidos (un {@code .upload-*.tmp} restante también es basura). */
    private static List<String> filesOnDisk() {
        try (Stream<Path> files = Files.list(storageDir)) {
            return files.map(p -> p.getFileName().toString()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] pdf() {
        return "%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n"
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] polyglotPdf() {
        return "%PDF-1.4\n<html><body><script>alert(document.cookie)</script></body></html>\n%%EOF\n"
                .getBytes(StandardCharsets.US_ASCII);
    }

    // --- Almacenamiento instrumentado ---

    /** Punto de parada de un solo uso: la petición avisa al llegar y espera a que el test la suelte. */
    static final class Gate {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        void pass() {
            entered.countDown();
            await(released);
        }

        void awaitEntered() {
            await(entered);
        }

        void release() {
            released.countDown();
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timeout en la puerta de test");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /** {@link LocalFileSystemStorage} real con contadores de streams y puertas opcionales. */
    static final class GatedStorage implements StorageService {

        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        private final LocalFileSystemStorage delegate;
        private final AtomicReference<Gate> afterStore = new AtomicReference<>();
        private final AtomicReference<Gate> beforeOpen = new AtomicReference<>();
        private final AtomicReference<Gate> beforeRead = new AtomicReference<>();

        GatedStorage(LocalFileSystemStorage delegate) {
            this.delegate = delegate;
        }

        /** La próxima subida se detiene justo después de escribir su fichero (con el gasto bloqueado). */
        Gate armStore() {
            return arm(afterStore);
        }

        /** La próxima descarga se detiene tras leer los metadatos y antes de abrir el fichero. */
        Gate armOpen() {
            return arm(beforeOpen);
        }

        /** La próxima descarga se detiene con el fichero ya abierto, antes de leer el primer byte. */
        Gate armRead() {
            return arm(beforeRead);
        }

        private static Gate arm(AtomicReference<Gate> slot) {
            Gate gate = new Gate();
            slot.set(gate);
            return gate;
        }

        @Override
        public void store(String key, InputStream content) throws IOException {
            delegate.store(key, content);
            Gate gate = afterStore.getAndSet(null);
            if (gate != null) {
                gate.pass();
            }
        }

        @Override
        public InputStream open(String key) throws IOException {
            Gate gate = beforeOpen.getAndSet(null);
            if (gate != null) {
                gate.pass();
            }
            InputStream in = delegate.open(key);
            opened.incrementAndGet();
            return new Tracked(in, beforeRead.getAndSet(null));
        }

        @Override
        public void delete(String key) throws IOException {
            delegate.delete(key);
        }

        private final class Tracked extends FilterInputStream {

            private Gate beforeFirstRead;
            private boolean isClosed;

            Tracked(InputStream in, Gate beforeFirstRead) {
                super(in);
                this.beforeFirstRead = beforeFirstRead;
            }

            @Override
            public int read() throws IOException {
                gate();
                return super.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                gate();
                return super.read(b, off, len);
            }

            private void gate() {
                if (beforeFirstRead != null) {
                    Gate gate = beforeFirstRead;
                    beforeFirstRead = null;
                    gate.pass();
                }
            }

            @Override
            public void close() throws IOException {
                if (!isClosed) {
                    isClosed = true;
                    closed.incrementAndGet();
                }
                super.close();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GatedStorageConfig {

        @Bean
        @Primary
        GatedStorage gatedStorage(LocalStorageProperties properties) {
            return new GatedStorage(new LocalFileSystemStorage(properties.baseDir()));
        }
    }
}
