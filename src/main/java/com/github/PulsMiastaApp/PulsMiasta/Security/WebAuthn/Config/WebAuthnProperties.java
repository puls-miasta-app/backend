package com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.Config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * WebAuthn / Passkey configuration properties.
 * <p>
 * Example application.properties:
 * <pre>
 *   webauthn.rp-id=example.com
 *   webauthn.rp-name=PulsMiasta
 *   webauthn.allowed-origins=https://example.com,http://localhost:3000
 *   webauthn.challenge-ttl-seconds=300
 * </pre>
 * <p>
 * The RP ID must be the effective domain of the origin(s) your frontend runs on.
 * For mobile apps using react-native-passkey the origin is typically the associated domain.
 */
@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "webauthn")
public class WebAuthnProperties {

    /**
     * Relying Party ID — must be the registrable domain suffix of all allowed origins.
     * Example: "example.com" covers "https://app.example.com" and "https://admin.example.com".
     */
    private String rpId = "localhost";

    /**
     * Human-readable relying party name shown to the user in the passkey dialog.
     */
    private String rpName = "PulsMiasta";

    /**
     * Allowed origins. The origin in each WebAuthn ceremony response must be in this list.
     * For mobile (react-native-passkey): add "android:apk-key-hash:..." or use associated domains.
     */
    private Set<String> allowedOrigins = Set.of("http://localhost:3000", "https://localhost:3000");

    /**
     * How long a challenge is valid (seconds). After this time the ceremony must restart.
     * Default: 5 minutes.
     */
    private long challengeTtlSeconds = 300;

}
