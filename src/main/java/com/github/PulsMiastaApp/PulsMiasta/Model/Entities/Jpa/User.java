package com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_pesel_blind_index", columnList = "pesel_blind_index", unique = true),
        @Index(name = "idx_email", columnList = "email", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Argon2id blind index of the raw PESEL.
     * Stored as a 64-char hex string (256-bit output).
     * Used exclusively for lookup — the original PESEL is never persisted.
     */
    @Column(name = "pesel_blind_index", nullable = false, unique = true, length = 64)
    private String peselBlindIndex;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "role", nullable = false, length = 20)
    private String role = "USER";
}
