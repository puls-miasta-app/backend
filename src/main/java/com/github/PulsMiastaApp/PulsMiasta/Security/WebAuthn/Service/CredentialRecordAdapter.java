package com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.Service;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.UserCredential;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import org.springframework.security.web.webauthn.api.*;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adapts our JPA {@link UserCredential} entity to the Spring Security WebAuthn
 * {@link UserCredentialRepository} interface used internally by
 * {@link org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations}.
 * <p>
 * This is the bridge between the WebAuthn library's credential model and our MySQL-backed
 * credential storage. Uses the fully-qualified JPA repository to avoid import ambiguity
 * with Spring Security's same-named interface.
 */
@Component
public class CredentialRecordAdapter implements UserCredentialRepository {

    private final com.github.PulsMiastaApp.PulsMiasta.Repository.UserCredentialRepository jpaRepo;
    private final UserRepository userRepository;

    public CredentialRecordAdapter(
            com.github.PulsMiastaApp.PulsMiasta.Repository.UserCredentialRepository jpaRepo,
            UserRepository userRepository) {
        this.jpaRepo = jpaRepo;
        this.userRepository = userRepository;
    }

    // -------------------------------------------------------------------------
    // UserCredentialRepository (Spring Security WebAuthn interface)
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public void save(CredentialRecord record) {
        // Find the user by their WebAuthn user handle
        byte[] handleBytes = record.getUserEntityUserId().getBytes();
        User user = userRepository.findByWebauthnUserHandle(handleBytes)
                .orElseThrow(() -> new IllegalStateException(
                        "No user found for webauthn user handle — registration ceremony out of sync"));

        // Prevent duplicate credentials
        byte[] credId = record.getCredentialId().getBytes();
        if (jpaRepo.existsByCredentialId(credId)) {
            // Update existing (sign count update after authentication)
            jpaRepo.findByCredentialId(credId).ifPresent(existing -> {
                existing.setSignCount(record.getSignatureCount());
                existing.setBackupState(record.isBackupState());
                record.getLastUsed();
                existing.setLastUsedAt(record.getLastUsed());
                jpaRepo.save(existing);
            });
            return;
        }

        // New credential — persist
        UserCredential entity = new UserCredential();
        entity.setUser(user);
        entity.setCredentialId(credId);
        entity.setPublicKeyCose(record.getPublicKey().getBytes());
        entity.setSignCount(record.getSignatureCount());
        entity.setAaguid(formatAaguid());
        entity.setTransports(transportsToString(record.getTransports()));
        entity.setBackupEligible(record.isBackupEligible());
        entity.setBackupState(record.isBackupState());
        entity.setLabel(record.getLabel());
        // Store attestation bytes — required by Webauthn4J during assertion verification
        entity.setAttestationObject(
                record.getAttestationObject() != null ? record.getAttestationObject().getBytes() : null);
        entity.setClientDataJson(
                record.getAttestationClientDataJSON() != null ? record.getAttestationClientDataJSON().getBytes() : null);
        jpaRepo.save(entity);
    }

    @Override
    @Transactional
    public void delete(Bytes credentialId) {
        jpaRepo.findByCredentialId(credentialId.getBytes())
                .ifPresent(jpaRepo::delete);
    }

    @Override
    @Transactional(readOnly = true)
    public CredentialRecord findByCredentialId(Bytes credentialId) {
        return jpaRepo.findByCredentialId(credentialId.getBytes())
                .map(this::toCredentialRecord)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CredentialRecord> findByUserId(Bytes userId) {
        byte[] handleBytes = userId.getBytes();
        return userRepository.findByWebauthnUserHandle(handleBytes)
                .map(user -> jpaRepo.findAllByUserId(user.getId())
                        .stream()
                        .map(this::toCredentialRecord)
                        .toList())
                .orElse(List.of());
    }

    // -------------------------------------------------------------------------
    // Conversion helpers
    // -------------------------------------------------------------------------

    private CredentialRecord toCredentialRecord(UserCredential entity) {
        return ImmutableCredentialRecord.builder()
                .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                .credentialId(new Bytes(entity.getCredentialId()))
                .userEntityUserId(new Bytes(entity.getUser().getWebauthnUserHandle()))
                .publicKey(new ImmutablePublicKeyCose(entity.getPublicKeyCose()))
                .signatureCount(entity.getSignCount())
                .uvInitialized(true)
                .transports(parseTransports(entity.getTransports()))
                .backupEligible(entity.isBackupEligible())
                .backupState(entity.isBackupState())
                .attestationObject(
                        entity.getAttestationObject() != null ? new Bytes(entity.getAttestationObject()) : null)
                .attestationClientDataJSON(
                        entity.getClientDataJson() != null ? new Bytes(entity.getClientDataJson()) : null)
                .created(entity.getCreatedAt())
                .lastUsed(entity.getLastUsedAt())
                .label(entity.getLabel())
                .build();
    }

    private Set<AuthenticatorTransport> parseTransports(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(AuthenticatorTransport::valueOf)
                .collect(Collectors.toSet());
    }

    private String transportsToString(Set<AuthenticatorTransport> transports) {
        if (transports == null || transports.isEmpty()) return null;
        return transports.stream()
                .map(AuthenticatorTransport::getValue)
                .collect(Collectors.joining(","));
    }

    private String formatAaguid() {
        // AAGUID is parsed internally by Webauthn4J from the attestationObject.
        // We store a default placeholder; it can be extracted and updated later if needed.
        return "00000000-0000-0000-0000-000000000000";
    }

    private String generateLabel(CredentialRecord record) {
        if (record.getTransports() == null || record.getTransports().isEmpty()) {
            return "Security Key";
        }
        if (record.getTransports().contains(AuthenticatorTransport.INTERNAL)) {
            return "Built-in authenticator";
        }
        if (record.getTransports().contains(AuthenticatorTransport.USB)) {
            return "USB Security Key";
        }
        if (record.getTransports().contains(AuthenticatorTransport.NFC)) {
            return "NFC Security Key";
        }
        return "Security Key";
    }
}
