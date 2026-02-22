package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.UserCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserCredentialRepository extends JpaRepository<UserCredential, Long> {

    /**
     * Find a single credential by its raw credential ID bytes.
     */
    Optional<UserCredential> findByCredentialId(byte[] credentialId);

    /**
     * List all credentials for a given user (for management UI).
     */
    List<UserCredential> findAllByUserId(Long userId);

    /**
     * Check if a credential ID is already registered (duplicate prevention).
     */
    boolean existsByCredentialId(byte[] credentialId);

    /**
     * Delete a credential by its PK, but only if it belongs to the given user (authorization guard).
     */
    void deleteByIdAndUserId(Long id, Long userId);
}
