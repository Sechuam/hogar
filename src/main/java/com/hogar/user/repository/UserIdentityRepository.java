package com.hogar.user.repository;

import com.hogar.user.domain.IdentityProvider;
import com.hogar.user.domain.UserIdentity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    /** Identidad con su usuario ya cargado (es lo que se necesita al entrar con el proveedor). */
    @Query("select i from UserIdentity i join fetch i.user where i.provider = :provider and i.subject = :subject")
    Optional<UserIdentity> findByProviderAndSubject(IdentityProvider provider, String subject);
}
