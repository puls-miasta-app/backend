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
     * Whether email OTP 2FA is active for this account.
     * When true: login requires a one-time code sent to the user's email.
     * Disabled by default — user enables it (or TOTP / passkey) via account settings.
     * Mandatory for all admin roles; optional for USER role.
     */
    @Column(name = "email_otp_enabled", nullable = false)
    private boolean emailOtpEnabled = false;

    /** Województwo zarządzane przez admina (null dla USER i SUPER_ADMIN). */
    @Column(name = "managed_wojewodztwo", length = 100)
    private String managedWojewodztwo;

    /** Powiat zarządzany przez admina (wymagany dla ADMIN_POWIATU i niżej). */
    @Column(name = "managed_powiat", length = 100)
    private String managedPowiat;

    /** Gmina zarządzana przez admina (wymagana dla ADMIN_GMINY i niżej). */
    @Column(name = "managed_gmina", length = 100)
    private String managedGmina;

    /** Miasto zarządzane przez admina (wymagane dla ADMIN_MIASTA). */
    @Column(name = "managed_miasto", length = 100)
    private String managedMiasto;

    /** Gdy true — użytkownik musi zmienić hasło przy najbliższym logowaniu (ustawiane przez admina). */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    /**
     * Preferowana metoda 2FA wyświetlana jako pierwsza podczas logowania.
     * Wartości: "TOTP", "EMAIL_OTP", "PASSKEY" lub null (automatycznie — pierwsza dostępna).
     * Czyszczona automatycznie, gdy dana metoda zostaje wyłączona.
     */
    @Column(name = "two_factor_default_method", nullable = true, length = 20)
    private String twoFactorDefaultMethod;

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
