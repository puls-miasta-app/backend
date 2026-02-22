package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    /**
     * Lookup by the WebAuthn opaque user handle (16-byte BINARY).
     */
    Optional<User> findByWebauthnUserHandle(byte[] webauthnUserHandle);
}
