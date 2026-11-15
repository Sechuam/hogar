package com.hogar.invoice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hogar.expense.dto.ExpenseDeletedEvent;
import com.hogar.expense.dto.ExpenseRef;
import com.hogar.expense.service.ExpenseQueries;
import com.hogar.household.dto.PlanLimits;
import com.hogar.household.service.HouseholdQueries;
import com.hogar.invoice.config.InvoiceProperties;
import com.hogar.invoice.domain.Invoice;
import com.hogar.invoice.dto.InvoiceDownload;
import com.hogar.invoice.dto.InvoiceResponse;
import com.hogar.invoice.repository.InvoiceRepository;
import com.hogar.invoice.storage.LocalFileSystemStorage;
import com.hogar.invoice.storage.StorageService;
import com.hogar.shared.error.BadRequestException;
import com.hogar.shared.error.ConflictException;
import com.hogar.shared.error.DomainException;
import com.hogar.shared.error.ForbiddenException;
import com.hogar.shared.error.NotFoundException;
import com.hogar.shared.security.MembershipGuard;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;

/**
 * Servicio de facturas sin Spring: almacenamiento real en un {@link TempDir} y la sincronización de
 * transacciones activada a mano, para simular commit y rollback y ver qué queda en disco.
 */
class InvoiceServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");
    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID EXPENSE = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final byte[] PDF = "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_BYTES = 64;

    @TempDir
    Path storageDir;

    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final MembershipGuard guard = mock(MembershipGuard.class);
    private final ExpenseQueries expenses = mock(ExpenseQueries.class);
    private final HouseholdQueries households = mock(HouseholdQueries.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private StorageService storage;
    private InvoiceService service;

    @BeforeEach
    void setUp() {
        storage = spy(new LocalFileSystemStorage(storageDir));
        service = new InvoiceService(invoices, guard, expenses, households, storage, new InvoiceFileCleanup(storage),
                new InvoiceProperties(DataSize.ofBytes(MAX_BYTES)), transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(invoices.saveAndFlush(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(expenses.lockForModification(HOUSEHOLD, EXPENSE, USER)).thenReturn(ref());
        when(expenses.require(HOUSEHOLD, EXPENSE)).thenReturn(ref());
        when(households.lockPlanUsage(HOUSEHOLD)).thenReturn(PlanLimits.UNLIMITED);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    // --- Subida ---

    @Test
    void uploadStoresContentUnderARandomKeyAndSavesSanitizedMetadata() throws IOException {
        InvoiceResponse response = service.upload(HOUSEHOLD, EXPENSE, USER, "../../etc/passwd\"\r\nX: y",
                new ByteArrayResource(PDF));

        Invoice saved = savedInvoice();
        assertThat(saved.getStorageKey()).isNotEqualTo("passwd").satisfies(k -> UUID.fromString(k));
        assertThat(saved.getFilename()).isEqualTo("passwdX: y");
        assertThat(saved.getContentType()).isEqualTo("application/pdf");
        assertThat(saved.getSizeBytes()).isEqualTo(PDF.length);
        assertThat(saved.getSha256()).isEqualTo(sha256(PDF));
        assertThat(saved.getUploadedBy()).isEqualTo(USER);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(Files.readAllBytes(storageDir.resolve(saved.getStorageKey()))).isEqualTo(PDF);
        assertThat(response).isEqualTo(new InvoiceResponse(saved.getId(), EXPENSE, "application/pdf", PDF.length,
                "passwdX: y", sha256(PDF), USER, NOW));
    }

    /** Lo que recorta el saneador (255 puntos de código) tiene que caber en la entidad. */
    @Test
    void longEmojiFilenameIsTruncatedAndStored() {
        service.upload(HOUSEHOLD, EXPENSE, USER, "😀".repeat(300) + ".pdf", new ByteArrayResource(PDF));

        Invoice saved = savedInvoice();
        assertThat(saved.getFilename()).isEqualTo("😀".repeat(255));
        assertThat(storageDir.resolve(saved.getStorageKey())).exists();
    }

    @Test
    void uploadChecksMembershipThenLocksTheExpenseBeforeReadingTheFile() throws IOException {
        InputStreamSource content = mock(InputStreamSource.class);
        when(content.getInputStream()).thenReturn(new ByteArrayInputStream(PDF));

        service.upload(HOUSEHOLD, EXPENSE, USER, "f.pdf", content);

        InOrder order = inOrder(guard, expenses, invoices, content);
        order.verify(guard).requireMember(HOUSEHOLD, USER);
        order.verify(expenses).lockForModification(HOUSEHOLD, EXPENSE, USER);
        order.verify(invoices).existsByExpenseId(EXPENSE);
        order.verify(content).getInputStream();
    }

    @Test
    void nonMemberIsRejectedBeforeTouchingTheExpenseOrTheFile() {
        doThrow(new NotFoundException("household-not-found", "x")).when(guard).requireMember(HOUSEHOLD, USER);

        assertCode(() -> service.upload(HOUSEHOLD, EXPENSE, USER, "f.pdf", new ByteArrayResource(PDF)),
                "household-not-found");
        verifyNoInteractions(expenses, invoices);
        assertThat(filesOnDisk()).isEmpty();
    }

    @Test
    void userWhoCannotModifyTheExpenseCannotUpload() {
        when(expenses.lockForModification(HOUSEHOLD, EXPENSE, USER))
                .thenThrow(new ForbiddenException("expense-edit-forbidden", "x"));

        assertCode(() -> service.upload(HOUSEHOLD, EXPENSE, USER, "f.pdf", new ByteArrayResource(PDF)),
                "expense-edit-forbidden");
        assertThat(filesOnDisk()).isEmpty();
    }

    @Test
    void secondInvoiceIsAConflictAndWritesNothing() {
        when(invoices.existsByExpenseId(EXPENSE)).thenReturn(true);

        assertThatThrownBy(() -> service.upload(HOUSEHOLD, EXPENSE, USER, "f.pdf", new ByteArrayResource(PDF)))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo(InvoiceService.INVOICE_ALREADY_EXISTS);
        assertThat(filesOnDisk()).isEmpty();
        verify(invoices, never()).saveAndFlush(any());
    }

    @Test
    void emptyFileIsBadRequest() {
        assertThatThrownBy(() -> service.upload(HOUSEHOLD, EXPENSE, USER, "f.pdf", new ByteArrayResource(new byte[0])))
                .isInstanceOf(BadRequestException.class)
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo(InvoiceService.EMPTY_FILE);
        assertThat(filesOnDisk()).isEmpty();
    }

    @Test
    void executableRenamedAsPdfIsUnsupported() {
        byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0, (byte) 0xFF, (byte) 0xFF};

        assertThatThrownBy(() -> service.upload(HOUSEHOLD, EXPENSE, USER, "factura.pdf", new ByteArrayResource(exe)))
                .isInstanceOf(UnsupportedFileTypeException.class);
        assertThat(filesOnDisk()).isEmpty();
        verify(invoices, never()).saveAndFlush(any());
    }

    @Test
    void fileOverTheLimitIsRejectedAndLeavesNothingOnDisk() {
        byte[] big = new byte[MAX_BYTES + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);

        assertThatThrownBy(() -> service.upload(HOUSEHOLD, EXPENSE, USER, "f.pdf", new ByteArrayResource(big)))
                .isInstanceOf(FileTooLargeException.class);
        assertThat(filesOnDisk()).isEmpty();
    }

    @Test
    void fileExactlyAtTheLimitIsAccepted() {
        byte[] exact = new byte[MAX_BYTES];
        System.arraycopy(PDF, 0, exact, 0, PDF.length);

        assertThat(service.upload(HOUSEHOLD, EXPENSE, USER, "f.pdf", new ByteArrayResource(exact)).sizeBytes())
                .isEqualTo(MAX_BYTES);
    }

    @Test
    void readErrorFromTheClientIsUncheckedAndLeavesNothingOnDisk() throws IOException {
        InputStreamSource broken = mock(InputStreamSource.class);
        when(broken.getInputStream()).thenThrow(new IOException("connection reset"));

        assertThatThrownBy(() -> service.upload(HOUSEHOLD, EXPENSE, USER, "f.pdf", broken))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(filesOnDisk()).isEmpty();
    }

    @Test
    void rollbackAfterWritingDeletesTheFile() {
        service.upload(HOUSEHOLD, EXPENSE, USER, "f.pdf", new ByteArrayResource(PDF));
        assertThat(filesOnDisk()).hasSize(1);

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(filesOnDisk()).isEmpty();
    }

    @Test
    void invoiceThatDoesNotFitInThePlanIsRejectedAfterLockingTheExpenseAndTheFileIsDeletedOnRollback() {
        long max = 100;
        when(households.lockPlanUsage(HOUSEHOLD)).thenReturn(new PlanLimits(4, 1, max, 12));
        when(invoices.sumSizeBytes(HOUSEHOLD)).thenReturn(max - PDF.length + 1);

        assertCode(() -> service.upload(HOUSEHOLD, EXPENSE, USER, "f.pdf", new ByteArrayResource(PDF)),
                "plan-storage-limit");
        var order = inOrder(expenses, households);
        order.verify(expenses).lockForModification(HOUSEHOLD, EXPENSE, USER);
        order.verify(households).lockPlanUsage(HOUSEHOLD);
        verify(invoices, never()).saveAndFlush(any());

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(filesOnDisk()).isEmpty();
    }

    @Test
    void invoiceThatExactlyFillsThePlanIsAccepted() {
        when(households.lockPlanUsage(HOUSEHOLD)).thenReturn(new PlanLimits(4, 1, 100L, 12));
        when(invoices.sumSizeBytes(HOUSEHOLD)).thenReturn(100L - PDF.length);

        service.upload(HOUSEHOLD, EXPENSE, USER, "f.pdf", new ByteArrayResource(PDF));

        assertThat(savedInvoice().getSizeBytes()).isEqualTo(PDF.length);
    }

    @Test
    void databaseFailureOnInsertRollsBackAndTheFileIsDeleted() {
        when(invoices.saveAndFlush(any(Invoice.class))).thenThrow(new DataIntegrityViolationException("boom"));

        assertThatThrownBy(() -> service.upload(HOUSEHOLD, EXPENSE, USER, "f.pdf", new ByteArrayResource(PDF)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(filesOnDisk()).hasSize(1);

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(filesOnDisk()).isEmpty();
    }

    @Test
    void commitKeepsTheFile() {
        service.upload(HOUSEHOLD, EXPENSE, USER, "f.pdf", new ByteArrayResource(PDF));

        commit();

        assertThat(filesOnDisk()).containsExactly(savedInvoice().getStorageKey());
    }

    @Test
    void unknownOutcomeKeepsTheFileBecauseTheRowMayExist() {
        service.upload(HOUSEHOLD, EXPENSE, USER, "f.pdf", new ByteArrayResource(PDF));

        complete(TransactionSynchronization.STATUS_UNKNOWN);

        assertThat(filesOnDisk()).hasSize(1);
    }

    @Test
    void failureDeletingTheFileAfterRollbackIsOnlyLogged() throws IOException {
        service.upload(HOUSEHOLD, EXPENSE, USER, "f.pdf", new ByteArrayResource(PDF));
        doThrow(new IOException("disk gone")).when(storage).delete(anyString());

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(filesOnDisk()).hasSize(1);
    }

    // --- Metadatos y descarga ---

    @Test
    void metadataOfAnExistingInvoice() {
        Invoice invoice = existingInvoice(UUID.randomUUID().toString());

        assertThat(service.metadata(HOUSEHOLD, EXPENSE, USER)).isEqualTo(InvoiceMapper.toResponse(invoice));
        verify(expenses).require(HOUSEHOLD, EXPENSE);
        verify(expenses, never()).lockForModification(any(), any(), any());
    }

    @Test
    void metadataWithoutInvoiceIsNotFound() {
        assertCode(() -> service.metadata(HOUSEHOLD, EXPENSE, USER), InvoiceService.INVOICE_NOT_FOUND);
    }

    @Test
    void metadataOfAnExpenseOfAnotherHouseholdIsExpenseNotFound() {
        when(expenses.require(HOUSEHOLD, EXPENSE)).thenThrow(new NotFoundException("expense-not-found", "x"));

        assertCode(() -> service.metadata(HOUSEHOLD, EXPENSE, USER), "expense-not-found");
        verifyNoInteractions(invoices);
    }

    @Test
    void downloadReadsMetadataInATransactionAndOpensTheFileAfterwards() throws IOException {
        String key = UUID.randomUUID().toString();
        Files.write(storageDir.resolve(key), PDF);
        Invoice invoice = existingInvoice(key);

        InvoiceDownload download = service.download(HOUSEHOLD, EXPENSE, USER);

        try (InputStream content = download.content()) {
            assertThat(content.readAllBytes()).isEqualTo(PDF);
        }
        assertThat(download.metadata()).isEqualTo(InvoiceMapper.toResponse(invoice));
        InOrder order = inOrder(guard, transactionManager, storage);
        order.verify(transactionManager).getTransaction(any());
        order.verify(guard).requireMember(HOUSEHOLD, USER);
        order.verify(transactionManager).commit(any());
        order.verify(storage).open(key);
    }

    @Test
    void downloadWhenTheFileVanishedIsInvoiceNotFound() {
        existingInvoice(UUID.randomUUID().toString());

        assertCode(() -> service.download(HOUSEHOLD, EXPENSE, USER), InvoiceService.INVOICE_NOT_FOUND);
    }

    @Test
    void downloadWithAnUnreadableFileIsUnchecked() throws IOException {
        String key = UUID.randomUUID().toString();
        existingInvoice(key);
        doThrow(new IOException("EIO")).when(storage).open(key);

        assertThatThrownBy(() -> service.download(HOUSEHOLD, EXPENSE, USER)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void downloadByANonMemberNeverOpensAFile() throws IOException {
        doThrow(new NotFoundException("household-not-found", "x")).when(guard).requireMember(HOUSEHOLD, USER);

        assertCode(() -> service.download(HOUSEHOLD, EXPENSE, USER), "household-not-found");
        verify(storage, never()).open(anyString());
    }

    // --- Borrado ---

    @Test
    void deleteRemovesTheRowAndTheFileOnlyAfterCommit() throws IOException {
        String key = UUID.randomUUID().toString();
        Files.write(storageDir.resolve(key), PDF);
        Invoice invoice = existingInvoice(key);

        service.delete(HOUSEHOLD, EXPENSE, USER);

        verify(invoices).delete(invoice);
        assertThat(filesOnDisk()).containsExactly(key);
        commit();
        assertThat(filesOnDisk()).isEmpty();
    }

    @Test
    void deleteRolledBackKeepsTheFile() throws IOException {
        String key = UUID.randomUUID().toString();
        Files.write(storageDir.resolve(key), PDF);
        existingInvoice(key);

        service.delete(HOUSEHOLD, EXPENSE, USER);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(filesOnDisk()).containsExactly(key);
    }

    @Test
    void failureDeletingTheFileAfterCommitIsOnlyLogged() throws IOException {
        String key = UUID.randomUUID().toString();
        existingInvoice(key);
        doThrow(new IOException("disk gone")).when(storage).delete(key);
        service.delete(HOUSEHOLD, EXPENSE, USER);

        commit();

        verify(storage).delete(key);
    }

    @Test
    void deleteWithoutInvoiceIsNotFound() {
        assertCode(() -> service.delete(HOUSEHOLD, EXPENSE, USER), InvoiceService.INVOICE_NOT_FOUND);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void deleteByUserWhoCannotModifyTheExpenseIsForbidden() {
        existingInvoice(UUID.randomUUID().toString());
        when(expenses.lockForModification(HOUSEHOLD, EXPENSE, USER))
                .thenThrow(new ForbiddenException("expense-edit-forbidden", "x"));

        assertCode(() -> service.delete(HOUSEHOLD, EXPENSE, USER), "expense-edit-forbidden");
        verify(invoices, never()).delete(any());
    }

    // --- Borrado del gasto ---

    @Test
    void deletingTheExpenseDeletesTheInvoiceFileAfterCommit() throws IOException {
        String key = UUID.randomUUID().toString();
        Files.write(storageDir.resolve(key), PDF);
        existingInvoice(key);
        InvoiceExpenseListener listener = new InvoiceExpenseListener(invoices, new InvoiceFileCleanup(storage));

        listener.onExpenseDeleted(new ExpenseDeletedEvent(HOUSEHOLD, EXPENSE));

        assertThat(filesOnDisk()).containsExactly(key);
        commit();
        assertThat(filesOnDisk()).isEmpty();
    }

    @Test
    void deletingAnExpenseWithoutInvoiceSchedulesNothing() {
        new InvoiceExpenseListener(invoices, new InvoiceFileCleanup(storage))
                .onExpenseDeleted(new ExpenseDeletedEvent(HOUSEHOLD, EXPENSE));

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    // --- Utilidades ---

    private static ExpenseRef ref() {
        return new ExpenseRef(EXPENSE, HOUSEHOLD, USER, USER);
    }

    private Invoice existingInvoice(String storageKey) {
        Invoice invoice = Invoice.create(HOUSEHOLD, EXPENSE, storageKey, "application/pdf", PDF.length,
                sha256(PDF), "factura.pdf", USER, NOW);
        when(invoices.findByExpenseIdAndHouseholdId(EXPENSE, HOUSEHOLD)).thenReturn(Optional.of(invoice));
        return invoice;
    }

    private Invoice savedInvoice() {
        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoices).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static void commit() {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        synchronizations.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    }

    private static void complete(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(status));
    }

    private List<String> filesOnDisk() {
        try (Stream<Path> files = Files.list(storageDir)) {
            return files.map(p -> p.getFileName().toString()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo(code);
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
