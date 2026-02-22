package com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.DTO;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.UserCredential;

import java.time.Instant;
import java.util.Base64;

/**
 * Projection of a {@link UserCredential} returned to the authenticated user
 * for credential management (list / delete).
 *
 * The public key bytes are NOT included — there is no reason to expose them to the client.
 */
public record CredentialInfo(

        /** Database PK — used as identifier when deleting. */
        Long id,

        /** Credential ID in Base64url encoding (for display only). */
        String credentialId,

        /** Human-readable label (e.g. "iPhone Face ID", "YubiKey 5"). */
        String label,

        /** AAGUID identifying the authenticator model. */
        String aaguid,

        /** Whether this credential is a discoverable/resident key. */
        boolean backupEligible,

        /** Whether the credential is currently backed up (e.g. iCloud Keychain synced). */
        boolean backupState,

        Instant createdAt,
        Instant lastUsedAt

) {
    public static CredentialInfo from(UserCredential c) {
        return new CredentialInfo(
                c.getId(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(c.getCredentialId()),
                c.getLabel(),
                c.getAaguid(),
                c.isBackupEligible(),
                c.isBackupState(),
                c.getCreatedAt(),
                c.getLastUsedAt()
        );
    }
}
