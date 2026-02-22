package com.github.PulsMiastaApp.PulsMiasta.Repository;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Looks up a user by the pre-computed Argon2id blind index of their PESEL.
     * Never call this with a raw PESEL — always go through BlindIndexService first.
     */
    Optional<User> findByPeselBlindIndex(String peselBlindIndex);

    boolean existsByPeselBlindIndex(String peselBlindIndex);

    boolean existsByEmail(String email);
}
