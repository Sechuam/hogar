package com.hogar.shared.security;

import java.util.UUID;

/**
 * Contrato de autorización por hogar. Lo implementa la feature {@code household} y lo usan
 * todos los servicios que operan bajo {@code /api/households/{householdId}/**}.
 *
 * <p>Semántica de errores (ver D-005):
 * <ul>
 *   <li>El hogar no existe o el usuario no es miembro <b>activo</b> → {@code NotFoundException}
 *       con código {@code household-not-found} (no se revela su existencia).</li>
 *   <li>Es miembro pero no OWNER en una acción de OWNER → {@code ForbiddenException}
 *       con código {@code owner-role-required}.</li>
 * </ul>
 */
public interface MembershipGuard {

    void requireMember(UUID householdId, UUID userId);

    void requireOwner(UUID householdId, UUID userId);
}
