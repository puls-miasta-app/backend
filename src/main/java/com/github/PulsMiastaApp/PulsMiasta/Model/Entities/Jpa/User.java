package com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_email", columnList = "email", unique = true),
        @Index(name = "idx_webauthn_user_handle", columnList = "webauthn_user_handle", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "role", nullable = false, length = 20)
    private String role = "USER";

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    /**
     * WebAuthn user handle — opaque, unique, stable 16-byte identifier (UUID v4 as bytes).
     * Never changes, never encodes personal data. Used as userHandle in passkey ceremonies.
     * Generated once at registration time; null for legacy accounts (assigned on first passkey action).
     */
    @Column(name = "webauthn_user_handle", nullable = true, unique = true, columnDefinition = "BINARY(16)")
    private byte[] webauthnUserHandle;

    /**
     * TOTP (Time-based One-Time Password) shared secret in Base32 encoding.
     * Null when TOTP has not been configured for this account.
     */
    @Column(name = "totp_secret", nullable = true, length = 100)
    private String totpSecret;

    /**
     * Whether TOTP 2FA is active for this account.
     * When true: login requires a TOTP code in addition to password.
     * Mandatory for ADMIN role; optional for USER role.
     */
    @Column(name = "totp_enabled", nullable = false)
    private boolean totpEnabled = false;

    /**
     * Ensures a webauthnUserHandle is assigned. Call before any WebAuthn ceremony.
     * Idempotent — safe to call multiple times.
     */
    public void ensureWebauthnUserHandle() {
        if (this.webauthnUserHandle == null) {
            UUID uuid = UUID.randomUUID();
            byte[] bytes = new byte[16];
            long msb = uuid.getMostSignificantBits();
            long lsb = uuid.getLeastSignificantBits();
            for (int i = 0; i < 8; i++) {
                bytes[i] = (byte) (msb >>> (56 - 8 * i));
                bytes[i + 8] = (byte) (lsb >>> (56 - 8 * i));
            }
            this.webauthnUserHandle = bytes;
        }
    }
}
