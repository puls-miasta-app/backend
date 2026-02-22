package com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.DTO;

import java.util.List;

/**
 * Sent to the client to start a passkey registration ceremony.
 *
 * Maps to the Web Authentication API {@code PublicKeyCredentialCreationOptions}.
 * The client passes this object directly to {@code navigator.credentials.create()} (web)
 * or the equivalent react-native-passkey / expo-passkeys call (mobile).
 *
 * All binary values are Base64url-encoded (no padding) as required by the WebAuthn JSON serialisation spec.
 */
public record RegistrationBeginResponse(

        /** Opaque token the client MUST echo in the finish request to allow server-side challenge lookup. */
        String sessionKey,

        /** RP identity. */
        RpInfo rp,

        /** User identity. */
        UserInfo user,

        /** The challenge bytes (Base64url). */
        String challenge,

        /** Supported public key algorithms, ordered by preference. */
        List<PubKeyCredParam> pubKeyCredParams,

        /** Ceremony timeout in milliseconds. */
        long timeout,

        /**
         * Already-registered credentials the authenticator MUST NOT create duplicates for.
         * Prevents the same device from registering twice.
         */
        List<AllowedCredential> excludeCredentials,

        /** Authenticator selection hints. */
        AuthenticatorSelection authenticatorSelection,

        /** Attestation preference — "none" for maximum privacy. */
        String attestation

) {
    public record RpInfo(String id, String name) {}

    public record UserInfo(
            /** Base64url-encoded user handle (16 bytes). */
            String id,
            /** Login name (email). Not used as an identifier by the authenticator. */
            String name,
            /** Display name shown in the passkey dialog. */
            String displayName
    ) {}

    public record PubKeyCredParam(String type, int alg) {}

    public record AllowedCredential(String type, String id, List<String> transports) {}

    public record AuthenticatorSelection(
            /** "platform" for Face ID / Touch ID / Windows Hello; "cross-platform" for USB keys. null = no preference. */
            String authenticatorAttachment,
            /** Whether a resident/discoverable key is required. "preferred" balances compatibility and UX. */
            String residentKey,
            /** Whether user verification (biometric/PIN) is required. "preferred" allows fallback. */
            String userVerification
    ) {}
}
