package com.hogar.invoice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hogar.expense.domain.ExpenseCategory;
import com.hogar.expense.domain.SharedExpense;
import com.hogar.expense.domain.split.ShareAllocation;
import com.hogar.expense.domain.split.SplitResult;
import com.hogar.expense.repository.SharedExpenseRepository;
import com.hogar.household.domain.Household;
import com.hogar.household.domain.SplitPolicy;
import com.hogar.household.repository.HouseholdRepository;
import com.hogar.invoice.domain.Invoice;
import com.hogar.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class InvoiceRepositoryIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");
    private static final String SHA = "0123456789abcdef".repeat(4);

    @Autowired
    private InvoiceRepository invoices;

    @Autowired
    private SharedExpenseRepository expenses;

    @Autowired
    private HouseholdRepository households;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    private UUID householdId;
    private UUID otherHouseholdId;
    private UUID ana;

    @BeforeEach
    void setUp() {
        householdId = households.saveAndFlush(Household.create("Piso", null, null, NOW)).getId();
        otherHouseholdId = households.saveAndFlush(Household.create("Otro", null, null, NOW)).getId();
        ana = users().create("Ana").id();
    }

    @Test
    void persistsAndFindsByExpense() {
        UUID expenseId = expense(householdId);
        Invoice created = invoices.saveAndFlush(invoice(householdId, expenseId));
        em.clear();

        Invoice loaded = invoices.findByExpenseIdAndHouseholdId(expenseId, householdId).orElseThrow();

        assertThat(loaded).isEqualTo(created);
        assertThat(loaded.isNew()).isFalse();
        assertThat(loaded.getHouseholdId()).isEqualTo(householdId);
        assertThat(loaded.getExpenseId()).isEqualTo(expenseId);
        assertThat(loaded.getStorageKey()).isEqualTo(created.getStorageKey());
        assertThat(loaded.getContentType()).isEqualTo("application/pdf");
        assertThat(loaded.getSizeBytes()).isEqualTo(2048);
        assertThat(loaded.getSha256()).isEqualTo(SHA);
        assertThat(loaded.getFilename()).isEqualTo("factura ñ.pdf");
        assertThat(loaded.getUploadedBy()).isEqualTo(ana);
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(invoices.findByExpenseId(expenseId)).contains(created);
        assertThat(invoices.existsByExpenseId(expenseId)).isTrue();
    }

    /** {@code varchar(255)} cuenta caracteres (puntos de código), no unidades UTF-16 ni bytes. */
    @Test
    void filenameOf255EmojisFitsTheColumn() {
        String name = "😀".repeat(Invoice.MAX_FILENAME_LENGTH);
        Invoice created = invoices.saveAndFlush(Invoice.create(householdId, expense(householdId),
                UUID.randomUUID().toString(), "application/pdf", 2048, SHA, name, ana, NOW));
        em.clear();

        assertThat(invoices.findById(created.getId()).orElseThrow().getFilename()).isEqualTo(name);
    }

    @Test
    void findersIgnoreOtherExpensesAndHouseholds() {
        UUID expenseId = expense(householdId);
        UUID withoutInvoice = expense(householdId);
        invoices.saveAndFlush(invoice(householdId, expenseId));

        assertThat(invoices.findByExpenseIdAndHouseholdId(expenseId, otherHouseholdId)).isEmpty();
        assertThat(invoices.findByExpenseIdAndHouseholdId(withoutInvoice, householdId)).isEmpty();
        assertThat(invoices.findByExpenseId(withoutInvoice)).isEmpty();
        assertThat(invoices.existsByExpenseId(withoutInvoice)).isFalse();
    }

    @Test
    void saveOfANewInvoiceInsertsWithoutPreviousSelect() {
        Invoice invoice = invoice(householdId, expense(householdId));

        // Persistable: save() hace persist y devuelve la misma instancia (sin merge); tras el INSERT deja de ser nueva.
        assertThat(invoices.saveAndFlush(invoice)).isSameAs(invoice);
        assertThat(invoice.isNew()).isFalse();
    }

    @Test
    void oneInvoicePerExpense() {
        UUID expenseId = expense(householdId);
        invoices.saveAndFlush(invoice(householdId, expenseId));

        assertThatThrownBy(() -> invoices.saveAndFlush(invoice(householdId, expenseId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void storageKeyIsUnique() {
        Invoice first = invoices.saveAndFlush(invoice(householdId, expense(householdId)));
        Invoice second = invoices.saveAndFlush(invoice(householdId, expense(householdId)));

        assertThatThrownBy(() -> jdbc.update("update invoices set storage_key = ? where id = ?",
                first.getStorageKey(), second.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invoiceCannotPointToAnExpenseOfAnotherHousehold() {
        UUID foreignExpense = expense(otherHouseholdId);

        assertThatThrownBy(() -> invoices.saveAndFlush(invoice(householdId, foreignExpense)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invoiceRequiresAnExistingExpense() {
        assertThatThrownBy(() -> invoices.saveAndFlush(invoice(householdId, UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingTheExpenseCascadesToItsInvoice() {
        UUID expenseId = expense(householdId);
        UUID keptExpenseId = expense(householdId);
        Invoice invoice = invoices.saveAndFlush(invoice(householdId, expenseId));
        Invoice kept = invoices.saveAndFlush(invoice(householdId, keptExpenseId));
        em.clear();

        jdbc.update("delete from shared_expenses where id = ?", expenseId);

        assertThat(invoiceRows(invoice.getId())).isZero();
        assertThat(invoiceRows(kept.getId())).isEqualTo(1);
    }

    @Test
    void invoiceCanBeDeleted() {
        UUID expenseId = expense(householdId);
        Invoice invoice = invoices.saveAndFlush(invoice(householdId, expenseId));
        em.clear();

        invoices.delete(invoices.findByExpenseId(expenseId).orElseThrow());
        invoices.flush();

        assertThat(invoiceRows(invoice.getId())).isZero();
        assertThat(invoices.existsByExpenseId(expenseId)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "update invoices set size_bytes = 0 where id = ?",
            "update invoices set content_type = 'text/html' where id = ?",
            "update invoices set content_type = 'APPLICATION/PDF' where id = ?",
            "update invoices set sha256 = upper(sha256) where id = ?",
            "update invoices set sha256 = repeat('g', 64) where id = ?",
            "update invoices set filename = '  ' where id = ?",
            "update invoices set filename = '../etc/passwd' where id = ?",
            "update invoices set filename = 'a\\b' where id = ?",
            "update invoices set filename = 'a\"b' where id = ?",
            "update invoices set filename = 'a' || chr(10) || 'b' where id = ?",
            "update invoices set storage_key = '' where id = ?",
            "update invoices set uploaded_by = gen_random_uuid() where id = ?",
            "update invoices set household_id = gen_random_uuid() where id = ?"
    })
    void databaseChecksRejectInvalidRows(String update) {
        Invoice invoice = invoices.saveAndFlush(invoice(householdId, expense(householdId)));

        assertThatThrownBy(() -> jdbc.update(update, invoice.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void contentTypeCheckAcceptsEverySupportedType() {
        Invoice invoice = invoices.saveAndFlush(invoice(householdId, expense(householdId)));

        for (String contentType : Invoice.SUPPORTED_CONTENT_TYPES) {
            assertThat(jdbc.update("update invoices set content_type = ? where id = ?", contentType, invoice.getId()))
                    .isEqualTo(1);
        }
    }

    private Invoice invoice(UUID household, UUID expenseId) {
        return Invoice.create(household, expenseId, UUID.randomUUID().toString(), "application/pdf", 2048, SHA,
                "factura ñ.pdf", ana, NOW);
    }

    private UUID expense(UUID household) {
        SplitResult split = new SplitResult(SplitPolicy.EQUAL,
                List.of(new ShareAllocation(ana, new BigDecimal("10"), BigDecimal.ONE)), Set.of());
        return expenses.saveAndFlush(SharedExpense.create(household, ana, ana, new BigDecimal("10"), "EUR", "Luz",
                ExpenseCategory.ELECTRICITY, LocalDate.of(2026, 3, 5), split, NOW)).getId();
    }

    private int invoiceRows(UUID invoiceId) {
        return jdbc.queryForObject("select count(*) from invoices where id = ?", Integer.class, invoiceId);
    }
}
