package com.hogar.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.hogar.expense.FinanceApiSupport;
import com.hogar.expense.dto.ExpenseResponse;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.invoice.dto.InvoiceResponse;
import com.hogar.support.TestUsers.TestUser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Facturas de punta a punta: HTTP → servicio → PostgreSQL y disco. El almacenamiento apunta a un
 * directorio temporal propio de esta clase (para comprobar exactamente qué ficheros quedan) y el
 * tamaño máximo se baja a {@value #MAX_BYTES} bytes para probar el 413 sin mover megas.
 */
class InvoiceIT extends FinanceApiSupport {

    private static final int MAX_BYTES = 4096;
    private static final LocalDate MARCH = LocalDate.of(2026, 3, 10);

    @TempDir
    static Path storageDir;

    @DynamicPropertySource
    static void invoiceProperties(DynamicPropertyRegistry registry) {
        registry.add("hogar.storage.local.base-dir", () -> storageDir.toString());
        registry.add("hogar.invoices.max-size", () -> MAX_BYTES + "B");
    }

    /** Hogar de Ana (OWNER) con Luis, Eva y Pablo; gasto creado por Luis y pagado por Eva. */
    private record Flat(UUID id, TestUser ana, TestUser luis, TestUser eva, TestUser pablo, UUID expenseId) {
    }

    private Flat flat() {
        TestUser ana = users().create("Ana");
        TestUser luis = users().create("Luis");
        TestUser eva = users().create("Eva");
        TestUser pablo = users().create("Pablo");
        UUID id = createHousehold(ana, SplitPolicy.EQUAL);
        join(ana, id, luis, eva, pablo);
        ExpenseResponse expense = createExpense(luis, id, expense("40.00", MARCH, eva.id()));
        return new Flat(id, ana, luis, eva, pablo, expense.id());
    }

    // --- Subida y descarga ---

    static Stream<Arguments> supportedFiles() {
        return Stream.of(
                Arguments.of("factura.pdf", "application/pdf", pdf()),
                Arguments.of("ticket.jpg", "image/jpeg", image("jpg")),
                Arguments.of("ticket.png", "image/png", image("png")),
                Arguments.of("ticket.webp", "image/webp", webp()));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("supportedFiles")
    void uploadedInvoiceIsDownloadedByteForByteWithSafeHeaders(String filename, String contentType, byte[] content) {
        Flat f = flat();
        int filesBefore = filesOnDisk().size();

        // El cliente declara octet-stream: el tipo guardado sale de los magic bytes.
        ResponseEntity<String> created = upload(f.luis(), f.id(), f.expenseId(), filename,
                MediaType.APPLICATION_OCTET_STREAM_VALUE, content);

        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation().getPath()).isEqualTo(invoicePath(f.id(), f.expenseId()));
        InvoiceResponse invoice = read(created, InvoiceResponse.class);
        assertThat(invoice.expenseId()).isEqualTo(f.expenseId());
        assertThat(invoice.contentType()).isEqualTo(contentType);
        assertThat(invoice.sizeBytes()).isEqualTo(content.length);
        assertThat(invoice.sha256()).isEqualTo(sha256(content));
        assertThat(invoice.filename()).isEqualTo(filename);
        assertThat(invoice.uploadedBy()).isEqualTo(f.luis().id());
        assertThat(filesOnDisk()).hasSize(filesBefore + 1).contains(storageKey(f.expenseId()));

        // Pablo no puede modificar el gasto, pero como miembro sí descarga.
        ResponseEntity<byte[]> download = download(f.pablo(), f.id(), f.expenseId());

        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getBody()).isEqualTo(content);
        HttpHeaders headers = download.getHeaders();
        assertThat(headers.getContentType()).isEqualTo(MediaType.parseMediaType(contentType));
        assertThat(headers.getContentLength()).isEqualTo(content.length);
        assertThat(headers.getContentDisposition().isAttachment()).isTrue();
        assertThat(headers.getContentDisposition().getFilename()).isEqualTo(filename);
        if (filename.chars().allMatch(c -> c >= 0x20 && c < 0x7F)) {
            // Nombre ASCII legible tal cual, sin palabra codificada RFC 2047.
            assertThat(headers.getFirst(HttpHeaders.CONTENT_DISPOSITION))
                    .isEqualTo("attachment; filename=\"" + filename + "\"");
        }
        assertThat(headers.getCacheControl()).isEqualTo("private, no-store");
        assertThat(headers.get("X-Content-Type-Options")).containsExactly("nosniff");
    }

    @Test
    void metadataIsVisibleToAnyMember() {
        Flat f = flat();
        InvoiceResponse uploaded = read(upload(f.eva(), f.id(), f.expenseId(), "luz.pdf", "application/pdf", pdf()),
                InvoiceResponse.class);

        ResponseEntity<String> response = get(invoicePath(f.id(), f.expenseId()) + "/metadata", f.pablo());

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(read(response, InvoiceResponse.class)).usingRecursiveComparison().ignoringFields("createdAt")
                .isEqualTo(uploaded);
        assertThat(tree(response).has("storageKey")).isFalse();
    }

    @Test
    void maliciousFilenameIsSanitizedAndNeverReachesTheDisk() {
        Flat f = flat();
        String malicious = "../../etc/passwd\"\r\nX: y";

        ResponseEntity<String> created = uploadRaw(f.luis(), f.id(), f.expenseId(), malicious, pdf());

        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        String stored = read(created, InvoiceResponse.class).filename();
        assertThat(stored).isEqualTo("passwdX: y");
        assertThat(filesOnDisk()).doesNotContain("passwd", stored).contains(storageKey(f.expenseId()));
        assertThat(storageDir.getParent().resolve("etc")).doesNotExist();

        ResponseEntity<byte[]> download = download(f.ana(), f.id(), f.expenseId());
        String disposition = download.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).doesNotContain("\r", "\n").startsWith("attachment;");
        assertThat(download.getHeaders().get("X")).isNull();
        assertThat(ContentDisposition.parse(disposition).getFilename()).isEqualTo(stored);
    }

    @Test
    void nonAsciiFilenameRoundTripsThroughFilenameStar() {
        Flat f = flat();
        upload(f.luis(), f.id(), f.expenseId(), "factura año €.pdf", "application/pdf", pdf());

        String disposition = download(f.ana(), f.id(), f.expenseId()).getHeaders()
                .getFirst(HttpHeaders.CONTENT_DISPOSITION);

        assertThat(disposition).contains("filename*=UTF-8''");
        assertThat(ContentDisposition.parse(disposition).getFilename()).isEqualTo("factura año €.pdf");
    }

    // --- Contenido no válido ---

    @Test
    void executableRenamedAsPdfIsUnsupportedAndNothingIsStored() {
        Flat f = flat();
        int filesBefore = filesOnDisk().size();
        byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 0, 0};

        assertProblem(upload(f.luis(), f.id(), f.expenseId(), "factura.pdf", "application/pdf", exe),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-file-type");

        assertNoInvoice(f);
        assertThat(filesOnDisk()).hasSize(filesBefore);
    }

    @Test
    void emptyFileIsBadRequest() {
        Flat f = flat();

        assertProblem(upload(f.luis(), f.id(), f.expenseId(), "vacia.pdf", "application/pdf", new byte[0]),
                HttpStatus.BAD_REQUEST, "empty-file");
        assertNoInvoice(f);
    }

    @Test
    void fileOverTheLimitIsPayloadTooLargeAndLeavesNothingOnDisk() {
        Flat f = flat();
        int filesBefore = filesOnDisk().size();
        byte[] big = new byte[MAX_BYTES + 1];
        byte[] header = pdf();
        System.arraycopy(header, 0, big, 0, header.length);

        assertProblem(upload(f.luis(), f.id(), f.expenseId(), "grande.pdf", "application/pdf", big),
                HttpStatus.PAYLOAD_TOO_LARGE, "file-too-large");

        assertNoInvoice(f);
        assertThat(filesOnDisk()).hasSize(filesBefore);
    }

    @Test
    void missingFilePartIsBadRequest() {
        Flat f = flat();
        HttpHeaders headers = f.luis().bearer();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("otro", "x");

        ResponseEntity<String> response = rest.exchange(invoicePath(f.id(), f.expenseId()), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertNoInvoice(f);
    }

    @Test
    void secondUploadIsAConflictAndKeepsTheFirstInvoice() {
        Flat f = flat();
        InvoiceResponse first = read(upload(f.luis(), f.id(), f.expenseId(), "a.pdf", "application/pdf", pdf()),
                InvoiceResponse.class);
        int filesBefore = filesOnDisk().size();

        assertProblem(upload(f.ana(), f.id(), f.expenseId(), "b.png", "image/png", image("png")),
                HttpStatus.CONFLICT, "invoice-already-exists");

        assertThat(metadata(f.ana(), f).id()).isEqualTo(first.id());
        assertThat(filesOnDisk()).hasSize(filesBefore);
    }

    // --- Permisos y aislamiento ---

    @Test
    void onlyPayerCreatorOrOwnerCanUploadOrDelete() {
        Flat f = flat();
        String path = invoicePath(f.id(), f.expenseId());

        assertProblem(upload(f.pablo(), f.id(), f.expenseId(), "f.pdf", "application/pdf", pdf()),
                HttpStatus.FORBIDDEN, "expense-edit-forbidden");
        assertThat(upload(f.eva(), f.id(), f.expenseId(), "f.pdf", "application/pdf", pdf()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertProblem(call(HttpMethod.DELETE, path, f.pablo(), null), HttpStatus.FORBIDDEN, "expense-edit-forbidden");
        assertThat(download(f.pablo(), f.id(), f.expenseId()).getStatusCode()).isEqualTo(HttpStatus.OK);

        // El OWNER puede borrar aunque no sea pagador ni creador.
        assertThat(call(HttpMethod.DELETE, path, f.ana(), null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(upload(f.ana(), f.id(), f.expenseId(), "g.pdf", "application/pdf", pdf()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void nonMemberGetsHouseholdNotFoundOnEveryEndpoint() {
        Flat f = flat();
        upload(f.luis(), f.id(), f.expenseId(), "f.pdf", "application/pdf", pdf());
        TestUser stranger = users().create("Extraño");
        String path = invoicePath(f.id(), f.expenseId());

        assertProblem(upload(stranger, f.id(), f.expenseId(), "f.pdf", "application/pdf", pdf()),
                HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(get(path, stranger), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(get(path + "/metadata", stranger), HttpStatus.NOT_FOUND, "household-not-found");
        assertProblem(call(HttpMethod.DELETE, path, stranger, null), HttpStatus.NOT_FOUND, "household-not-found");
        assertThat(metadata(f.ana(), f).filename()).isEqualTo("f.pdf");
    }

    @Test
    void expenseOfAnotherHouseholdIsExpenseNotFound() {
        Flat f = flat();
        Flat other = flat();
        upload(other.luis(), other.id(), other.expenseId(), "ajena.pdf", "application/pdf", pdf());
        // Ruta del hogar de Ana con el gasto del otro hogar: Ana es OWNER aquí, pero el gasto no es de este hogar.
        String path = invoicePath(f.id(), other.expenseId());

        assertProblem(upload(f.ana(), f.id(), other.expenseId(), "f.pdf", "application/pdf", pdf()),
                HttpStatus.NOT_FOUND, "expense-not-found");
        assertProblem(get(path, f.ana()), HttpStatus.NOT_FOUND, "expense-not-found");
        assertProblem(get(path + "/metadata", f.ana()), HttpStatus.NOT_FOUND, "expense-not-found");
        assertProblem(call(HttpMethod.DELETE, path, f.ana(), null), HttpStatus.NOT_FOUND, "expense-not-found");
        assertThat(metadata(other.ana(), other).filename()).isEqualTo("ajena.pdf");
    }

    @Test
    void expenseWithoutInvoiceIsInvoiceNotFound() {
        Flat f = flat();
        String path = invoicePath(f.id(), f.expenseId());

        assertProblem(get(path, f.pablo()), HttpStatus.NOT_FOUND, "invoice-not-found");
        assertProblem(get(path + "/metadata", f.pablo()), HttpStatus.NOT_FOUND, "invoice-not-found");
        assertProblem(call(HttpMethod.DELETE, path, f.ana(), null), HttpStatus.NOT_FOUND, "invoice-not-found");
    }

    // --- El gasto indica si tiene factura ---

    @Test
    void expenseReportsWhetherItHasAnInvoiceInListAndDetail() {
        Flat f = flat();
        ExpenseResponse without = createExpense(f.luis(), f.id(), expense("10.00", MARCH, null));
        assertThat(without.hasInvoice()).isFalse();

        upload(f.luis(), f.id(), f.expenseId(), "f.pdf", "application/pdf", pdf());

        assertThat(getExpense(f.pablo(), f.id(), f.expenseId()).hasInvoice()).isTrue();
        assertThat(listExpenses(f.pablo(), f.id(), "").content())
                .extracting(ExpenseResponse::id, ExpenseResponse::hasInvoice)
                .containsExactlyInAnyOrder(
                        tuple(f.expenseId(), true),
                        tuple(without.id(), false));

        call(HttpMethod.DELETE, invoicePath(f.id(), f.expenseId()), f.eva(), null);
        assertThat(getExpense(f.pablo(), f.id(), f.expenseId()).hasInvoice()).isFalse();
    }

    // --- Consistencia BD ↔ disco ---

    @Test
    void deletingTheInvoiceRemovesRowAndFile() {
        Flat f = flat();
        upload(f.luis(), f.id(), f.expenseId(), "f.pdf", "application/pdf", pdf());
        String key = storageKey(f.expenseId());
        assertThat(storageDir.resolve(key)).exists();

        ResponseEntity<String> response = call(HttpMethod.DELETE, invoicePath(f.id(), f.expenseId()), f.eva(), null);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(storageDir.resolve(key)).doesNotExist();
        assertNoInvoice(f);
        // Se puede volver a subir otra.
        assertThat(upload(f.luis(), f.id(), f.expenseId(), "f2.pdf", "application/pdf", pdf()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void deletingTheExpenseRemovesTheInvoiceFile() {
        Flat f = flat();
        upload(f.luis(), f.id(), f.expenseId(), "f.pdf", "application/pdf", pdf());
        String key = storageKey(f.expenseId());

        ResponseEntity<String> response = call(HttpMethod.DELETE, expensePath(f.id(), f.expenseId()), f.luis(), null);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(storageDir.resolve(key)).doesNotExist();
        assertThat(invoiceRows(f.expenseId())).isZero();
        assertProblem(get(invoicePath(f.id(), f.expenseId()), f.ana()), HttpStatus.NOT_FOUND, "expense-not-found");
    }

    @Test
    void forbiddenExpenseDeletionKeepsTheInvoiceFile() {
        Flat f = flat();
        upload(f.luis(), f.id(), f.expenseId(), "f.pdf", "application/pdf", pdf());
        String key = storageKey(f.expenseId());

        assertProblem(call(HttpMethod.DELETE, expensePath(f.id(), f.expenseId()), f.pablo(), null),
                HttpStatus.FORBIDDEN, "expense-edit-forbidden");

        assertThat(storageDir.resolve(key)).exists();
        assertThat(download(f.pablo(), f.id(), f.expenseId()).getBody()).isEqualTo(pdf());
    }

    /**
     * Fallo real de PostgreSQL al insertar la fila, después de escribir el fichero: un trigger
     * temporal rechaza solo este nombre de fichero. La transacción hace rollback y el fichero recién
     * escrito se borra.
     */
    @Test
    void databaseFailureAfterWritingTheFileLeavesNoOrphan() {
        Flat f = flat();
        String poison = "fallo-bd-" + UUID.randomUUID() + ".pdf";
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "it_fail_invoice_" + suffix;
        String trigger = "it_fail_invoice_trg_" + suffix;
        jdbc.execute("create function " + function + "() returns trigger language plpgsql as $$ "
                + "begin raise exception 'fallo simulado'; end $$");
        jdbc.execute("create trigger " + trigger + " before insert on invoices for each row "
                + "when (new.filename = '" + poison + "') execute function " + function + "()");
        List<String> filesBefore;
        try {
            filesBefore = filesOnDisk();

            ResponseEntity<String> response = upload(f.luis(), f.id(), f.expenseId(), poison, "application/pdf",
                    pdf());

            assertProblem(response, HttpStatus.INTERNAL_SERVER_ERROR, "internal-error");
        } finally {
            jdbc.execute("drop trigger " + trigger + " on invoices");
            jdbc.execute("drop function " + function + "()");
        }
        assertThat(filesOnDisk()).containsExactlyInAnyOrderElementsOf(filesBefore);
        assertNoInvoice(f);
    }

    // --- Utilidades ---

    private static String invoicePath(UUID householdId, UUID expenseId) {
        return expensePath(householdId, expenseId) + "/invoice";
    }

    private ResponseEntity<String> upload(TestUser user, UUID householdId, UUID expenseId, String filename,
                                          String declaredContentType, byte[] content) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(declaredContentType));
        partHeaders.setContentDisposition(ContentDisposition.formData().name("file")
                .filename(filename, StandardCharsets.UTF_8).build());
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(content, partHeaders));
        HttpHeaders headers = user.bearer();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange(invoicePath(householdId, expenseId), HttpMethod.POST, new HttpEntity<>(body, headers),
                String.class);
    }

    /**
     * Multipart escrito a mano: el nombre viaja tal cual en {@code filename*} (RFC 5987, con CR/LF y
     * comillas codificados en %XX), como lo mandaría un cliente hostil, sin que el cliente HTTP lo sanee.
     */
    private ResponseEntity<String> uploadRaw(TestUser user, UUID householdId, UUID expenseId, String filename,
                                             byte[] content) {
        String boundary = "hogar-" + UUID.randomUUID();
        String encoded = java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename*=UTF-8''" + encoded + "\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        HttpHeaders headers = user.bearer();
        headers.setContentType(MediaType.parseMediaType("multipart/form-data; boundary=" + boundary));
        return rest.exchange(invoicePath(householdId, expenseId), HttpMethod.POST,
                new HttpEntity<>(body.toByteArray(), headers), String.class);
    }

    private ResponseEntity<byte[]> download(TestUser user, UUID householdId, UUID expenseId) {
        return rest.exchange(invoicePath(householdId, expenseId), HttpMethod.GET, new HttpEntity<>(user.bearer()),
                byte[].class);
    }

    private InvoiceResponse metadata(TestUser user, Flat f) {
        ResponseEntity<String> response = get(invoicePath(f.id(), f.expenseId()) + "/metadata", user);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return read(response, InvoiceResponse.class);
    }

    private void assertNoInvoice(Flat f) {
        assertProblem(get(invoicePath(f.id(), f.expenseId()) + "/metadata", f.ana()), HttpStatus.NOT_FOUND,
                "invoice-not-found");
        assertThat(invoiceRows(f.expenseId())).isZero();
    }

    private int invoiceRows(UUID expenseId) {
        return jdbc.queryForObject("select count(*) from invoices where expense_id = ?", Integer.class, expenseId);
    }

    private String storageKey(UUID expenseId) {
        return jdbc.queryForObject("select storage_key from invoices where expense_id = ?", String.class, expenseId);
    }

    private static List<String> filesOnDisk() {
        try (Stream<Path> files = Files.list(storageDir)) {
            return files.map(p -> p.getFileName().toString()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- Ficheros mínimos reales ---

    private static byte[] pdf() {
        return """
                %PDF-1.4
                1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
                2 0 obj << /Type /Pages /Kids [] /Count 0 >> endobj
                trailer << /Root 1 0 R >>
                %%EOF
                """.getBytes(StandardCharsets.US_ASCII);
    }

    /** Imagen 1×1 real generada con ImageIO ({@code jpg} o {@code png}). */
    private static byte[] image(String format) {
        BufferedImage pixel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        pixel.setRGB(0, 0, 0x2E7D32);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            assertThat(ImageIO.write(pixel, format, out)).isTrue();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** WebP sin pérdida de 1×1 píxel (RIFF + VP8L), el mínimo válido. */
    private static byte[] webp() {
        return HexFormat.of().parseHex(
                "524946461a000000574542505650384c0d0000002f0000001007101111888808fe0700");
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
