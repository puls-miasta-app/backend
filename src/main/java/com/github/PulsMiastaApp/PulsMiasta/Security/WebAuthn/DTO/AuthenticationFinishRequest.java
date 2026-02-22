package com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.DTO;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ClientType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Sent by the client to complete a passkey authentication ceremony.
 *
 * Contains the authenticator's assertion response as returned by
 * {@code navigator.credentials.get()} serialised to JSON.
 */
public record AuthenticationFinishRequest(

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

        /** The assertion response. */
        @NotNull AssertionResponse response,

        /**
         * Whether to issue a remember-me token alongside the session cookie.
         * Mobile: sets 90-day remember-me. Web: sets 30-day.
         */
        boolean rememberMe,

        /**
         * Client type — determines cookie TTLs and remember-me duration.
         * Must be WEB or MOBILE.
         */
        @NotNull ClientType clientType

) {
    public record AssertionResponse(
            @NotBlank String clientDataJSON,
            @NotBlank String authenticatorData,
            @NotBlank String signature,
            /** Base64url-encoded user handle returned by the authenticator (may be null for non-discoverable). */
            String userHandle
    ) {}
}
