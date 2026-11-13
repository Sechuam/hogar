package com.hogar.auth.repository;

import com.hogar.auth.domain.RefreshToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revoca todos los tokens aún no revocados de una familia (detección de reutilización).
     * Actualización masiva: vacía y limpia el contexto de persistencia para no dejar entidades
     * obsoletas cargadas.
     *
     * @return número de tokens revocados
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(UUID familyId, Instant now);

    /** Revoca todas las sesiones de un usuario (p. ej. al cambiar la contraseña). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllByUserId(UUID userId, Instant now);
}
