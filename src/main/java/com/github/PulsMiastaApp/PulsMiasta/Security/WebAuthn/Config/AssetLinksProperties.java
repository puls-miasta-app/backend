package com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.Config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Android Digital Asset Links configuration — used to build the JSON served at
 * {@code GET /.well-known/assetlinks.json}. That file tells Android (via Google's
 * verification servers) that the configured Android app is allowed to share
 * passkeys with the Relying Party domain ({@code webauthn.rp-id}).
 * <p>
 * Both {@code delegate_permission/common.handle_all_urls} (deep linking) and
 * {@code delegate_permission/common.get_login_creds} (passkeys via Credential
 * Manager) are emitted in a single statement — Credential Manager specifically
 * requires the latter, so a file with only {@code handle_all_urls} will fail
 * validation on passkey registration.
 * <p>
 * The fingerprint list supports multiple values so debug (local keystore) and
 * release (Play App Signing) certificates can both be trusted simultaneously.
 */
@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "android.assetlinks")
public class AssetLinksProperties {

    /**
     * Android application ID (must exactly match the app's {@code applicationId}
     * in its build config, e.g. {@code com.anonymous.pulsmiasta}).
     */
    private String packageName = "com.anonymous.pulsmiasta";

    /**
     * SHA-256 certificate fingerprints, one per signing certificate that may
     * sign the APK / AAB. Each entry is a colon-separated uppercase hex string
     * (32 bytes, as produced by {@code keytool -list -v}).
     * <p>
     * Typical contents:
     * <ul>
     *   <li>the debug keystore fingerprint — required for {@code expo run:android}
     *       and Android Studio debug builds. Obtain with:
     *       <pre>keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey \
     *       -storepass android -keypass android | grep SHA256</pre></li>
     *   <li>the Play App Signing fingerprint — required for Play-distributed builds.
     *       Copy from Play Console → App signing → "App signing key certificate".</li>
     *   <li>(optional) the upload-key fingerprint, if you use Play App Signing with
     *       a separate upload key for internal testing tracks.</li>
     * </ul>
     * <p>
     * Leave empty to disable the endpoint content (the JSON will still be served,
     * but with no fingerprints Credential Manager cannot validate the app).
     */
    private List<String> sha256Fingerprints = List.of();
}
