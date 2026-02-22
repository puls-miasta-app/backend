package com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Persistent WebAuthn credential (passkey) bound to a user account.
 * <p>
 * One user can have multiple credentials (different devices:
 * Face ID on iPhone, Touch ID on MacBook, USB security key, etc.).
 * All authenticator types share the same WebAuthn wire protocol — the backend
 * is fully agnostic to the physical authenticator.
 */
@Entity
@Table(
        name = "user_credentials",
        indexes = {
                @Index(name = "idx_uc_credential_id", columnList = "credential_id", unique = true),
                @Index(name = "idx_uc_user_id", columnList = "user_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class UserCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to the owning user. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * The raw credential ID bytes returned by the authenticator.
     * Up to 1023 bytes per spec; stored as VARBINARY.
     */
    @Column(name = "credential_id", nullable = false, unique = true, columnDefinition = "VARBINARY(1024)")
    private byte[] credentialId;

    /**
     * Public key in COSE format (CBOR-encoded).
     * For P-256 keys this is typically ~77 bytes; we allow 2 KB for future algorithms.
     */
    @Column(name = "public_key_cose", nullable = false, columnDefinition = "VARBINARY(2048)")
    private byte[] publicKeyCose;

    /**
     * Signature counter — monotonically increasing value used to detect
     * cloned authenticators. 0 is valid for platform authenticators (e.g. Face ID)
     * which do not increment.
     */
    @Column(name = "sign_count", nullable = false)
    private long signCount;

    /**
     * AAGUID — 16-byte UUID identifying the authenticator model/family.
     * Stored as hex string (32 chars). May be all-zeros for privacy-preserving authenticators.
     */
    @Column(name = "aaguid", nullable = false, length = 36)
    private String aaguid;

    /**
     * Supported transports declared by the authenticator (e.g. "internal", "usb", "ble", "nfc").
     * Stored as a comma-separated list; used to populate allowCredentials.transports in assertions.
     */
    @Column(name = "transports", nullable = true, length = 255)
    private String transports;

    /**
     * Whether the credential is a discoverable / resident key.
     * Discoverable credentials allow username-less (conditional UI) login.
     */
    @Column(name = "backup_eligible", nullable = false)
    private boolean backupEligible;

    @Column(name = "backup_state", nullable = false)
    private boolean backupState;

    /**
     * User-visible label for this credential (e.g. "iPhone Face ID", "YubiKey 5").
     * Populated from User-Agent heuristics at registration; user can rename.
     */
    @Column(name = "label", nullable = true, length = 255)
    private String label;

    /**
     * Raw attestationObject CBOR bytes from the registration ceremony.
     * Required by Webauthn4J to reconstruct the authenticator data during
     * assertion verification (it reads the public key and flags from here).
     * Stored as MEDIUMBLOB — attestation objects can be several KB with full chains.
     */
    @Column(name = "attestation_object", nullable = true, columnDefinition = "MEDIUMBLOB")
    private byte[] attestationObject;

    /**
     * Raw clientDataJSON bytes from the registration ceremony.
     * Also required by Webauthn4J during assertion verification.
     */
    @Column(name = "client_data_json", nullable = true, columnDefinition = "MEDIUMBLOB")
    private byte[] clientDataJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = true)
    private Instant lastUsedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
