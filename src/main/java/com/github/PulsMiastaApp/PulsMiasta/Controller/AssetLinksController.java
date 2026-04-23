package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.Config.AssetLinksProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Serves the Android Digital Asset Links file consumed by Credential Manager
 * during passkey registration and authentication on Android.
 *
 * <h2>What Android expects</h2>
 * <pre>
 *   GET https://&lt;webauthn.rp-id&gt;/.well-known/assetlinks.json
 * </pre>
 * served over <strong>real HTTPS</strong> (self-signed certs are rejected — Google's
 * verification servers fetch the file on Android's behalf). The response body must
 * be a JSON array of statements, each declaring which relations the RP domain
 * grants to which Android app.
 *
 * <h2>Why both relations?</h2>
 * Credential Manager specifically looks for
 * {@code delegate_permission/common.get_login_creds}. A file that only declares
 * {@code handle_all_urls} (common for deep-link setups) will validate for App
 * Links but fail for passkeys. We emit both so the same file supports deep
 * linking and Credential Manager.
 *
 * <h2>Deployment note — context path</h2>
 * The Spring Boot app is mounted under {@code server.servlet.context-path=/api},
 * so this controller actually binds to {@code /api/.well-known/assetlinks.json}
 * inside the container. The reverse proxy / ingress in front of the backend must
 * expose the URL at the root of the {@code webauthn.rp-id} host, e.g. nginx:
 * <pre>
 *   location = /.well-known/assetlinks.json {
 *       proxy_pass http://backend/api/.well-known/assetlinks.json;
 *   }
 * </pre>
 * If the backend is deployed without a context path (e.g. behind an ingress that
 * strips {@code /api}), the controller is reachable at the expected URL directly.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Android Asset Links", description = "Digital Asset Links file consumed by Android Credential Manager for passkeys")
public class AssetLinksController {

    /**
     * Both relations are emitted in a single statement:
     * <ul>
     *   <li>{@code handle_all_urls} — App Links / deep linking.</li>
     *   <li>{@code get_login_creds} — required by Credential Manager for passkeys
     *       and password autofill. Without this relation the passkey ceremony
     *       aborts with a domain-association error.</li>
     * </ul>
     */
    private static final List<String> RELATIONS = List.of(
            "delegate_permission/common.handle_all_urls",
            "delegate_permission/common.get_login_creds"
    );

    private final AssetLinksProperties properties;

    @GetMapping(value = "/.well-known/assetlinks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Digital Asset Links statement for the Android app (public)")
    public ResponseEntity<List<Map<String, Object>>> assetLinks() {
        Map<String, Object> target = Map.of(
                "namespace", "android_app",
                "package_name", properties.getPackageName(),
                "sha256_cert_fingerprints", properties.getSha256Fingerprints()
        );
        Map<String, Object> statement = Map.of(
                "relation", RELATIONS,
                "target", target
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(List.of(statement));
    }
}
