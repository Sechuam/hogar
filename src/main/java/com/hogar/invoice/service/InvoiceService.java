package com.hogar.invoice.service;

import com.hogar.expense.service.ExpenseQueries;
import com.hogar.invoice.config.InvoiceProperties;
import com.hogar.invoice.domain.Invoice;
import com.hogar.invoice.dto.InvoiceDownload;
import com.hogar.invoice.dto.InvoiceResponse;
import com.hogar.invoice.repository.InvoiceRepository;
import com.hogar.invoice.service.content.FileType;
import com.hogar.invoice.service.content.FileTypeDetector;
import com.hogar.invoice.service.content.FilenameSanitizer;
import com.hogar.invoice.service.content.MeteredInputStream;
import com.hogar.invoice.storage.StorageService;
import com.hogar.shared.error.BadRequestException;
import com.hogar.shared.error.ConflictException;
import com.hogar.shared.error.NotFoundException;
import com.hogar.shared.security.MembershipGuard;
import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Factura adjunta a un gasto (como mucho una). Orden de comprobaciones en todas las operaciones:
 * membresía del hogar ({@code household-not-found}), gasto del hogar ({@code expense-not-found}) y,
 * al modificar, permiso sobre el gasto ({@code expense-edit-forbidden}) con su fila bloqueada, lo
 * que serializa subida/borrado de la factura con la edición y el borrado del gasto.
 *
 * <p>Consistencia BD ↔ disco: ver {@link InvoiceFileCleanup}. El {@code userId} lo pasa el controller.
 */
@Service
public class InvoiceService {

    static final String INVOICE_NOT_FOUND = "invoice-not-found";
    static final String INVOICE_ALREADY_EXISTS = "invoice-already-exists";
    static final String EMPTY_FILE = "empty-file";

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private final InvoiceRepository invoices;
    private final MembershipGuard guard;
    private final ExpenseQueries expenses;
    private final StorageService storage;
    private final InvoiceFileCleanup cleanup;
    private final InvoiceProperties properties;
    private final TransactionTemplate readOnlyTransaction;
    private final Clock clock;

    InvoiceService(InvoiceRepository invoices, MembershipGuard guard, ExpenseQueries expenses, StorageService storage,
                   InvoiceFileCleanup cleanup, InvoiceProperties properties,
                   PlatformTransactionManager transactionManager, Clock clock) {
        this.invoices = invoices;
        this.guard = guard;
        this.expenses = expenses;
        this.storage = storage;
        this.cleanup = cleanup;
        this.properties = properties;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.clock = clock;
    }

    /**
     * Adjunta la factura al gasto. El tipo se detecta por magic bytes (el {@code Content-Type} y la
     * extensión del cliente se ignoran) y el contenido se copia en streaming al almacenamiento bajo
     * una clave UUID nueva, contando bytes y calculando el SHA-256. El nombre original solo se guarda
     * saneado, como metadato. Si la transacción no llega a hacer commit, el fichero se borra.
     *
     * @param originalFilename nombre que envió el cliente (puede ser nulo o malicioso)
     * @throws ConflictException             {@code invoice-already-exists}
     * @throws BadRequestException           {@code empty-file}
     * @throws UnsupportedFileTypeException  si no es PDF, JPEG, PNG ni WebP
     * @throws FileTooLargeException         si supera {@code hogar.invoices.max-size}
     */
    @Transactional
    public InvoiceResponse upload(UUID householdId, UUID expenseId, UUID userId, String originalFilename,
                                  InputStreamSource content) {
        guard.requireMember(householdId, userId);
        expenses.lockForModification(householdId, expenseId, userId);
        if (invoices.existsByExpenseId(expenseId)) {
            throw new ConflictException(INVOICE_ALREADY_EXISTS,
                    "El gasto ya tiene una factura. Bórrala antes de subir otra.");
        }
        String storageKey = UUID.randomUUID().toString();
        StoredContent stored = store(storageKey, content);
        // Tras escribir con éxito: si store() falla, ya limpia su temporal y no hay nada que borrar.
        cleanup.deleteIfRolledBack(storageKey);

        Invoice invoice = Invoice.create(householdId, expenseId, storageKey, stored.type().contentType(),
                stored.sizeBytes(), stored.sha256(), FilenameSanitizer.sanitize(originalFilename), userId,
                Instant.now(clock));
        // saveAndFlush: cualquier fallo de BD salta aquí, dentro de la transacción → rollback → borrado.
        return InvoiceMapper.toResponse(invoices.saveAndFlush(invoice));
    }

    /** Metadatos de la factura; cualquier miembro del hogar. */
    @Transactional(readOnly = true)
    public InvoiceResponse metadata(UUID householdId, UUID expenseId, UUID userId) {
        return InvoiceMapper.toResponse(find(householdId, expenseId, userId));
    }

    /**
     * Abre la factura para descargarla; cualquier miembro del hogar. Los metadatos se leen en una
     * transacción de solo lectura que se cierra <b>antes</b> de abrir el fichero: la conexión a BD no
     * queda retenida mientras dura la transferencia. Quien llama cierra el stream.
     */
    public InvoiceDownload download(UUID householdId, UUID expenseId, UUID userId) {
        StoredInvoice stored = readOnlyTransaction.execute(status -> {
            Invoice invoice = find(householdId, expenseId, userId);
            return new StoredInvoice(InvoiceMapper.toResponse(invoice), invoice.getStorageKey());
        });
        try {
            return new InvoiceDownload(stored.metadata(), storage.open(stored.storageKey()));
        } catch (FileNotFoundException e) {
            // Carrera con un borrado ya confirmado (el fichero se borra tras el commit): ya no existe.
            log.warn("Fichero de factura {} no encontrado en el almacenamiento", stored.storageKey());
            throw invoiceNotFound();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo abrir el fichero de la factura", e);
        }
    }

    /** Borra la factura (pagador, creador u OWNER). El fichero se borra tras el commit. */
    @Transactional
    public void delete(UUID householdId, UUID expenseId, UUID userId) {
        guard.requireMember(householdId, userId);
        expenses.lockForModification(householdId, expenseId, userId);
        Invoice invoice = invoices.findByExpenseIdAndHouseholdId(expenseId, householdId)
                .orElseThrow(InvoiceService::invoiceNotFound);
        invoices.delete(invoice);
        cleanup.deleteAfterCommit(invoice.getStorageKey());
    }

    private Invoice find(UUID householdId, UUID expenseId, UUID userId) {
        guard.requireMember(householdId, userId);
        expenses.require(householdId, expenseId);
        return invoices.findByExpenseIdAndHouseholdId(expenseId, householdId)
                .orElseThrow(InvoiceService::invoiceNotFound);
    }

    /** Detecta el tipo por la cabecera y copia el contenido con límite de tamaño y SHA-256. */
    private StoredContent store(String storageKey, InputStreamSource content) {
        try (InputStream in = new BufferedInputStream(content.getInputStream())) {
            in.mark(FileTypeDetector.HEADER_LENGTH);
            byte[] header = in.readNBytes(FileTypeDetector.HEADER_LENGTH);
            in.reset();
            if (header.length == 0) {
                throw new BadRequestException(EMPTY_FILE, "El fichero está vacío.");
            }
            FileType type = FileTypeDetector.detect(header).orElseThrow(UnsupportedFileTypeException::new);
            MeteredInputStream metered = new MeteredInputStream(in, properties.maxSize().toBytes());
            storage.store(storageKey, metered);
            return new StoredContent(type, metered.getCount(), metered.sha256Hex());
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar el fichero de la factura", e);
        }
    }

    private static NotFoundException invoiceNotFound() {
        return new NotFoundException(INVOICE_NOT_FOUND, "El gasto no tiene factura.");
    }

    private record StoredContent(FileType type, long sizeBytes, String sha256) {
    }

    private record StoredInvoice(InvoiceResponse metadata, String storageKey) {
    }
}
