package com.hogar.invoice.controller;

import com.hogar.invoice.dto.InvoiceDownload;
import com.hogar.invoice.dto.InvoiceResponse;
import com.hogar.invoice.service.InvoiceService;
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
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<InvoiceResponse> upload(@PathVariable UUID householdId, @PathVariable UUID expenseId,
                                           @RequestPart("file") MultipartFile file) {
        InvoiceResponse created = invoices.upload(householdId, expenseId, currentUser.id(),
                file.getOriginalFilename(), file);
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri().build().toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * Contenido binario como adjunto. {@code Content-Disposition} lo construye {@link ContentDisposition}
     * (RFC 6266: {@code filename} + {@code filename*} en UTF-8) a partir del nombre ya saneado al subir;
     * {@code nosniff} evita que el navegador reinterprete el contenido.
     */
    @GetMapping
    ResponseEntity<Resource> download(@PathVariable UUID householdId, @PathVariable UUID expenseId) {
        InvoiceDownload download = invoices.download(householdId, expenseId, currentUser.id());
        InvoiceResponse metadata = download.metadata();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(metadata.contentType()));
        headers.setContentLength(metadata.sizeBytes());
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(metadata.filename(), StandardCharsets.UTF_8).build());
        headers.setCacheControl(CACHE_CONTROL);
        headers.set("X-Content-Type-Options", NOSNIFF);
        // El conversor de recursos cierra el stream al terminar de escribirlo.
        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(download.content()));
    }

    @GetMapping("/metadata")
    InvoiceResponse metadata(@PathVariable UUID householdId, @PathVariable UUID expenseId) {
        return invoices.metadata(householdId, expenseId, currentUser.id());
    }

    @DeleteMapping
    ResponseEntity<Void> delete(@PathVariable UUID householdId, @PathVariable UUID expenseId) {
        invoices.delete(householdId, expenseId, currentUser.id());
        return ResponseEntity.noContent().build();
    }
}
