package com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.DTO;

import java.util.List;

/**
 * Sent to the client to start a passkey authentication ceremony.
 * <p>
 * Maps to {@code PublicKeyCredentialRequestOptions}.
 * The client passes this to {@code navigator.credentials.get()} (web)
 * or the react-native-passkey equivalent (mobile).
 * <p>
 * If {@code allowCredentials} is empty, the ceremony is "discoverable" —
 * the authenticator shows all passkeys for this RP and the user picks one.
 * This enables username-less login.
 */
public record AuthenticationBeginResponse(

        /** Opaque token the client MUST echo in the finish request. */
        String sessionKey,

        /** The challenge bytes (Base64url). */
        String challenge,

        /** Ceremony timeout in milliseconds. */
        long timeout,

        /** RP ID — must match what was used at registration. */
        String rpId,

        /**
         * Pre-filtered list of credentials for the authenticator.
         * Empty list → discoverable / conditional UI flow (username-less).
         * Non-empty → targeted assertion (user provided email → we know their credentials).
         */
        List<AllowedCredential> allowCredentials,

        /** "required" / "preferred" / "discouraged". */
        String userVerification

) {
    public record AllowedCredential(String type, String id, List<String> transports) {
    }
}
