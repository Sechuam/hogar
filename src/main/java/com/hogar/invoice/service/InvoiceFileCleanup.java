package com.hogar.invoice.service;

import com.hogar.invoice.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Mantiene el disco coherente con la BD atando el borrado de ficheros al resultado de la
 * transacción en curso (contrato §4): la invariante es que <b>nunca queda una fila sin fichero</b>;
 * en el peor caso queda un fichero huérfano, que solo se registra en el log.
 *
 * <p>Requiere una transacción con sincronización activa (si no, {@link IllegalStateException}): un
 * borrado de fichero sin transacción a la que esperar sería un error de programación.
 */
@Component
class InvoiceFileCleanup {

    private static final Logger log = LoggerFactory.getLogger(InvoiceFileCleanup.class);

    private final StorageService storage;

    InvoiceFileCleanup(StorageService storage) {
        this.storage = storage;
    }

    /**
     * Borra el fichero recién escrito si la transacción hace rollback. Con un resultado
     * {@code STATUS_UNKNOWN} (fallo durante el propio commit) se conserva: la fila podría haberse
     * guardado y borrar el fichero la dejaría sin contenido.
     */
    void deleteIfRolledBack(String storageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    deleteQuietly(storageKey);
                }
            }
        });
    }

    /** Borra el fichero solo cuando la transacción (que borra su fila) ha hecho commit. */
    void deleteAfterCommit(String storageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteQuietly(storageKey);
            }
        });
    }

    /** Nunca propaga: la transacción ya está cerrada y no hay nada que deshacer. */
    void deleteQuietly(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (Exception e) {
            // Solo la clave (UUID opaco) y el tipo de error: nada de nombres de fichero ni rutas.
            log.warn("No se pudo borrar el fichero de factura {}; queda huérfano ({})", storageKey,
                    e.getClass().getSimpleName());
        }
    }
}
