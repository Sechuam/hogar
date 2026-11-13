package com.hogar.user.repository;

import com.hogar.user.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Busca por email exacto: pasar el email ya normalizado con {@link User#normalizeEmail}. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
