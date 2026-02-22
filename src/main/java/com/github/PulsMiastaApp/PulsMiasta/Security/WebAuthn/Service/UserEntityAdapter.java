package com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.Service;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;

/**
 * Adapts our JPA {@link User} entity to the Spring Security WebAuthn
 * {@link PublicKeyCredentialUserEntityRepository} interface.
 *
 * The user handle (id) is the {@code webauthnUserHandle} byte array stored on the User entity —
 * an opaque, stable, never-changing UUID-derived 16-byte value.
 * It must never encode personal data (GDPR / WebAuthn spec §6.1).
 *
 * This adapter is read-only for the WebAuthn library — "save" and "delete" operations
 * are handled by our own JPA layer (AuthService / PasskeyController).
 */
@Component
@RequiredArgsConstructor
public class UserEntityAdapter implements PublicKeyCredentialUserEntityRepository {

    private final UserRepository userRepository;

    @Override
    public PublicKeyCredentialUserEntity findById(Bytes userHandle) {
        byte[] handleBytes = userHandle.getBytes();
        return userRepository.findByWebauthnUserHandle(handleBytes)
                .map(this::toEntity)
                .orElse(null);
    }

    @Override
    public PublicKeyCredentialUserEntity findByUsername(String username) {
        // "username" in the WebAuthn library context is the account identifier shown to the user.
        // We use email as the login name.
        return userRepository.findByEmail(username)
                .map(this::toEntity)
                .orElse(null);
    }

    /**
     * Not used — user entity lifecycle is managed by our JPA layer.
     * The WebAuthn library calls this when it would create a new user entry,
     * but we always pre-create users through the standard registration flow.
     */
    @Override
    public void save(PublicKeyCredentialUserEntity entity) {
        // no-op: users are managed by AuthService
    }

    /**
     * Not used in our flow — credential deletion is cascaded through our own service.
     */
    @Override
    public void delete(Bytes userHandle) {
        // no-op
    }

    // -------------------------------------------------------------------------

    private PublicKeyCredentialUserEntity toEntity(User user) {
        return ImmutablePublicKeyCredentialUserEntity.builder()
                .id(new Bytes(user.getWebauthnUserHandle()))
                .name(user.getEmail())
                .displayName(user.getFirstName() + " " + user.getLastName())
                .build();
    }
}
