package com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Sent by the client to complete a passkey registration ceremony.
 *
 * Contains the authenticator's attestation response as returned by
 * {@code navigator.credentials.create()} serialised to JSON.
 * Binary fields are Base64url-encoded.
 */
public record RegistrationFinishRequest(

        /**
         * The sessionKey returned in {@link RegistrationBeginResponse}.
         * Used to retrieve and consume the server-side challenge from Redis.
         */
        @NotBlank String sessionKey,

        /**
         * The credential ID assigned by the authenticator (Base64url).
         */
        @NotBlank String id,

        /** Raw ID — same as {@code id} but raw bytes encoded as Base64url. */
        @NotBlank String rawId,

        /** Must be "public-key". */
        @NotBlank String type,

        /** The attestation response object. */
        @NotNull AttestationResponse response,

        /**
         * Optional human-readable label for this credential supplied by the client
         * (e.g. derived from User-Agent: "iPhone Face ID").
         * If null the server will generate a generic label.
         */
        String label

) {
    public record AttestationResponse(
            /** Base64url-encoded client data JSON. */
            @NotBlank String clientDataJSON,
            /** Base64url-encoded attestation object (CBOR). */
            @NotBlank String attestationObject,
            /** Comma-separated transports reported by the authenticator (optional). */
            String transports
    ) {}
}
