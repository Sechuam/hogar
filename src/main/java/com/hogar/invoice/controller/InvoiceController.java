package com.hogar.invoice.controller;

import com.hogar.invoice.dto.InvoiceDownload;
import com.hogar.invoice.dto.InvoiceResponse;
import com.hogar.invoice.service.InvoiceService;
import com.hogar.shared.config.ApiDoc;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.hogar.shared.security.CurrentUser;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Factura (única) de un gasto: subir, descargar, consultar metadatos y borrar. */
@RestController
@RequestMapping("/api/households/{householdId}/expenses/{expenseId}/invoice")
@Tag(name = ApiDoc.TAG_INVOICES)
class InvoiceController {

    static final String CACHE_CONTROL = "private, no-store";
    static final String NOSNIFF = "nosniff";

    private final InvoiceService invoices;
    private final CurrentUser currentUser;

    InvoiceController(InvoiceService invoices, CurrentUser currentUser) {
        this.invoices = invoices;
        this.currentUser = currentUser;
    }

    /** El {@code Content-Type} y el nombre de la parte son solo pistas del cliente: el servicio no se fía. */
    @Operation(summary = "Adjuntar la factura de un gasto (pagador, creador u OWNER)",
            description = "Petición `multipart/form-data` con la parte binaria `file`. Una factura por gasto. El tipo "
                    + "se detecta por el contenido (magic bytes), nunca por el `Content-Type` ni la extensión: solo "
                    + "PDF, JPEG, PNG o WebP. Tamaño máximo 10 MB. El nombre original se guarda saneado y solo es "
                    + "informativo.")
    @ApiResponse(responseCode = "201", description = "Factura guardada.",
            headers = @Header(name = "Location", description = "URL de la factura (la misma ruta del POST)",
                    schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "`empty-file`: el fichero está vacío." + ApiDoc.NL
            + "`malformed-multipart`: la petición multipart no es válida." + ApiDoc.NL
            + "`bad-request`: falta la parte `file`, no tiene nombre de fichero o UUID mal formado en la ruta.")
    @ApiResponse(responseCode = "403", description = "`expense-edit-forbidden`: solo el pagador, quien creó el gasto o "
            + "un OWNER pueden adjuntar la factura.")
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL + ApiDoc.EXPENSE_NOT_FOUND)
    @ApiResponse(responseCode = "409", description = "`invoice-already-exists`: el gasto ya tiene factura; bórrala "
            + "antes de subir otra.")
    @ApiResponse(responseCode = "413", description = "`file-too-large`: el fichero supera el tamaño máximo (10 MB).")
    @ApiResponse(responseCode = "415", description = "`unsupported-file-type`: el contenido no es PDF, JPEG, PNG ni "
            + "WebP." + ApiDoc.NL + "`unsupported-media-type`: la petición no es `multipart/form-data`.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<InvoiceResponse> upload(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID del gasto") @PathVariable UUID expenseId,
                                           @Parameter(description = "Fichero de la factura (PDF, JPEG, PNG o WebP; máx. 10 MB)") @RequestPart("file") MultipartFile file) {
        InvoiceResponse created = invoices.upload(householdId, expenseId, currentUser.id(),
                file.getOriginalFilename(), file);
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri().build().toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Contenido binario como adjunto. {@code Content-Disposition} lo construye {@link ContentDisposition}
     * a partir del nombre ya saneado al subir: un nombre ASCII va tal cual en {@code filename}; uno no
     * ASCII añade {@code filename*} en UTF-8 (RFC 6266). Con charset, Spring codifica también un nombre
     * ASCII como palabra RFC 2047 ({@code =?UTF-8?Q?...?=}), ilegible para clientes sencillos;
     * {@code nosniff} evita que el navegador reinterprete el contenido.
     */
    @Operation(summary = "Descargar la factura de un gasto",
            description = "Devuelve el contenido tal cual se subió, como adjunto, con el `Content-Type` detectado al "
                    + "subirlo (sea cual sea el `Accept`).")
    @ApiResponse(responseCode = "200", description = "Contenido binario de la factura.",
            content = {
                    @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "image/webp", schema = @Schema(type = "string", format = "binary"))},
            headers = {
                    @Header(name = "Content-Disposition", description = "`attachment` con `filename` (y `filename*` "
                            + "en UTF-8 si el nombre no es ASCII, RFC 6266)", schema = @Schema(type = "string",
                            example = "attachment; filename=\"ticket-mercadona.pdf\"")),
                    @Header(name = "Content-Length", description = "Tamaño en bytes",
                            schema = @Schema(type = "integer", format = "int64")),
                    @Header(name = "Cache-Control", description = "Nunca se cachea",
                            schema = @Schema(type = "string", example = "private, no-store")),
                    @Header(name = "X-Content-Type-Options", description = "El navegador no debe reinterpretar el tipo",
                            schema = @Schema(type = "string", example = "nosniff"))})
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL + ApiDoc.EXPENSE_NOT_FOUND + ApiDoc.NL + ApiDoc.INVOICE_NOT_FOUND)
    @GetMapping
    ResponseEntity<Resource> download(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID del gasto") @PathVariable UUID expenseId) {
        InvoiceDownload download = invoices.download(householdId, expenseId, currentUser.id());
        InvoiceResponse metadata = download.metadata();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(metadata.contentType()));
        headers.setContentLength(metadata.sizeBytes());
        headers.setContentDisposition(attachment(metadata.filename()));
        headers.setCacheControl(CACHE_CONTROL);
        headers.set("X-Content-Type-Options", NOSNIFF);
        // El conversor de recursos cierra el stream al terminar de escribirlo.
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(download.content()));
    }

    static ContentDisposition attachment(String filename) {
        boolean printableAscii = filename.chars().allMatch(c -> c >= 0x20 && c < 0x7F);
        ContentDisposition.Builder builder = ContentDisposition.attachment();
        return (printableAscii ? builder.filename(filename) : builder.filename(filename, StandardCharsets.UTF_8))
                .build();
    }

    @Operation(summary = "Consultar los metadatos de la factura",
            description = "Tipo, tamaño, nombre, hash SHA-256, quién la subió y cuándo; sin el contenido.")
    @ApiResponse(responseCode = "200", description = "Metadatos de la factura.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL + ApiDoc.EXPENSE_NOT_FOUND + ApiDoc.NL + ApiDoc.INVOICE_NOT_FOUND)
    @GetMapping("/metadata")
    InvoiceResponse metadata(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID del gasto") @PathVariable UUID expenseId) {
        return invoices.metadata(householdId, expenseId, currentUser.id());
    }

    @Operation(summary = "Borrar la factura de un gasto (pagador, creador u OWNER)",
            description = "Después se puede subir otra. El gasto no se modifica.")
    @ApiResponse(responseCode = "204", description = "Factura borrada.")
    @ApiResponse(responseCode = "400", description = ApiDoc.BAD_PATH)
    @ApiResponse(responseCode = "403", description = "`expense-edit-forbidden`: solo el pagador, quien creó el gasto o "
            + "un OWNER pueden borrar la factura.")
    @ApiResponse(responseCode = "404", description = ApiDoc.HOUSEHOLD_NOT_FOUND + ApiDoc.NL + ApiDoc.EXPENSE_NOT_FOUND + ApiDoc.NL + ApiDoc.INVOICE_NOT_FOUND)
    @DeleteMapping
    ResponseEntity<Void> delete(@Parameter(description = "ID del hogar") @PathVariable UUID householdId, @Parameter(description = "ID del gasto") @PathVariable UUID expenseId) {
        invoices.delete(householdId, expenseId, currentUser.id());
        return ResponseEntity.noContent().build();
    }
}
