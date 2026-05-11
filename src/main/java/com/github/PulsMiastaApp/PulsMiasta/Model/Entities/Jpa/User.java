package com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
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

    @Column(name = "totp_secret", nullable = true, length = 100)
    private String totpSecret;

    @Column(name = "totp_enabled", nullable = false)
    private boolean totpEnabled = false;

    @Column(name = "email_otp_enabled", nullable = false)
    private boolean emailOtpEnabled = false;

    /** Województwa zarządzane przez admina — może być wiele. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_managed_wojew",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "wojew_id")
    )
    private Set<Wojewodztwo> managedWojewodztwa = new HashSet<>();

    /** Powiaty zarządzane przez admina. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_managed_powiaty",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "powiat_id")
    )
    private Set<Powiat> managedPowiaty = new HashSet<>();

    /** Gminy zarządzane przez admina. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_managed_gminy",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "gmina_id")
    )
    private Set<Gmina> managedGminy = new HashSet<>();

    /** Miejscowości zarządzane przez admina (poziom ADMIN_MIASTA). */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_managed_miasta",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "miasto_id")
    )
    private Set<Miejscowosc> managedMiasta = new HashSet<>();

    /** Gdy true — użytkownik musi zmienić hasło przy najbliższym logowaniu (ustawiane przez admina). */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    @Column(name = "two_factor_default_method", nullable = true, length = 20)
    private String twoFactorDefaultMethod;

    @Column(name = "blocked", nullable = false)
    private boolean blocked = false;

    @Column(name = "blocked_at", nullable = true)
    private LocalDateTime blockedAt;

    @Column(name = "block_reason", columnDefinition = "TEXT", nullable = true)
    private String blockReason;

    /** ID admina który zablokował konto — denormalizacja (brak FK by uniknąć cyklu). */
    @Column(name = "blocked_by_admin_id", nullable = true)
    private Long blockedByAdminId;

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
