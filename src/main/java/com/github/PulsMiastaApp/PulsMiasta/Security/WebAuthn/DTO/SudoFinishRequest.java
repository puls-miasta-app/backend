package com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Sent by the client to complete a sudo mode passkey verification ceremony.
 * <p>
 * Unlike {@link AuthenticationFinishRequest}, this request does not issue a new session —
 * it only verifies the user's identity to elevate the existing session to sudo mode.
 */
public record SudoFinishRequest(

        /**
         * The sessionKey returned in {@link AuthenticationBeginResponse}.
         * Used to consume the stored challenge from Redis.
         */
        @NotBlank String sessionKey,

        /** Credential ID (Base64url). */
        @NotBlank String id,

        /** Raw credential ID (Base64url). */
        @NotBlank String rawId,

        /** Must be "public-key". */
        @NotBlank String type,

        /** The assertion response from the authenticator. */
        @NotNull AssertionResponse response

) {
    public record AssertionResponse(
            @NotBlank String clientDataJSON,
            @NotBlank String authenticatorData,
            @NotBlank String signature,
            /** Base64url-encoded user handle returned by the authenticator (may be null for non-discoverable). */
            String userHandle
    ) {
    }
}
