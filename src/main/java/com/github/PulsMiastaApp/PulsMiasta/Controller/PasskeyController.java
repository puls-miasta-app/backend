package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import com.github.PulsMiastaApp.PulsMiasta.Security.Annotation.RequireSudoMode;
import com.github.PulsMiastaApp.PulsMiasta.Security.Filter.AuthTokenFilter;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.AuthResult;
import com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.DTO.*;
import com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.Service.WebAuthnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * REST controller exposing the WebAuthn / Passkey API.
 *
 * <h2>Registration flow (requires active session)</h2>
 * <pre>
 *   POST /v1/auth/passkey/registration/begin    ← authenticated user starts ceremony
 *   POST /v1/auth/passkey/registration/finish   ← authenticator response sent here
 * </pre>
 *
 * <h2>Authentication flow (no session required)</h2>
 * <pre>
 *   POST /v1/auth/passkey/authentication/begin    ← returns challenge
 *   POST /v1/auth/passkey/authentication/finish   ← authenticator assertion; sets session cookie
 * </pre>
 *
 * <h2>Credential management (requires active session)</h2>
 * <pre>
 *   GET    /v1/auth/passkey/credentials           ← list registered passkeys
 *   DELETE /v1/auth/passkey/credentials/{id}      ← revoke a passkey
 * </pre>
 *
 * <h2>Mobile (React Native / Expo) notes</h2>
 * Mobile clients using {@code react-native-passkey} or {@code expo-passkeys} follow
 * the identical JSON protocol — Face ID, Touch ID, and USB security keys all use the
 * same WebAuthn wire format. The backend is authenticator-agnostic.
 *
 * <h2>Client type and cookies</h2>
 * After a successful authentication, the response sets {@code auth_token} (and optionally
 * {@code remember_me}) as HttpOnly cookies — identical to the PESEL+password login flow.
 */
@RestController
@RequestMapping("/v1/auth/passkey")
@RequiredArgsConstructor
@Tag(name = "Passkey / WebAuthn", description = "Passwordless authentication using platform authenticators (Face ID, Touch ID) and security keys (USB/NFC/BLE)")
public class PasskeyController {

    private final WebAuthnService webAuthnService;
    private final UserRepository userRepository;

    @Value("${auth.session.ttl-minutes}")
    private long sessionTtlMinutes;

    @Value("${auth.remember-me.web.ttl-days}")
    private long rememberMeWebDays;

    @Value("${auth.remember-me.mobile.ttl-days}")
    private long rememberMeMobileDays;

    // =========================================================================
    // Registration — Begin
    // =========================================================================

    /**
     * Starts a passkey registration ceremony for the currently authenticated user.
     * <p>
     * The returned JSON should be passed directly to {@code navigator.credentials.create()}
     * (web) or the equivalent native call (mobile).
     * <p>
     * The {@code sessionKey} field in the response MUST be echoed back in the finish request.
     */
    @PostMapping("/registration/begin")
    @Operation(summary = "Begin passkey registration (authenticated users only)")
    @RequireSudoMode
    public ResponseEntity<SuccessResponse<RegistrationBeginResponse>> registrationBegin(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request) {

        requireAuthenticated(principal);

        // Use the current session token as the ceremony session key so it is
        // cryptographically bound to the authenticated session without an extra round-trip.
        String sessionKey = extractSessionKey(request);

        var user = userRepository.findById(principal.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        RegistrationBeginResponse options = webAuthnService.beginRegistration(user, sessionKey);
        return ResponseEntity.ok(SuccessResponse.of(options));
    }

    // =========================================================================
    // Registration — Finish
    // =========================================================================

    /**
     * Completes the passkey registration ceremony.
     * <p>
     * The client sends the {@code PublicKeyCredential} returned by the authenticator,
     * plus the {@code sessionKey} received in the begin response.
     */
    @PostMapping("/registration/finish")
    @Operation(summary = "Complete passkey registration")
    @RequireSudoMode
    public ResponseEntity<SuccessResponse<String>> registrationFinish(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody RegistrationFinishRequest request) {

        requireAuthenticated(principal);

        var user = userRepository.findById(principal.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        webAuthnService.finishRegistration(user, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of("Passkey registered successfully"));
    }

    // =========================================================================
    // Authentication — Begin
    // =========================================================================

    /**
     * Starts a discoverable passkey authentication ceremony.
     *
     * <p>No user identifier is required — the authenticator (Face ID, Touch ID, USB security key)
     * selects the passkey for this RP autonomously and returns the {@code userHandle} in the
     * assertion, which the server uses to identify the user without any input from them.
     *
     * <p>The client may send an empty body or omit the body entirely.
     */
    @PostMapping("/authentication/begin")
    @Operation(summary = "Begin discoverable passkey authentication — no username required")
    public ResponseEntity<SuccessResponse<AuthenticationBeginResponse>> authenticationBegin() {
        // Generate a random, cryptographically opaque session key for this ceremony
        String sessionKey = UUID.randomUUID().toString();

        AuthenticationBeginResponse options = webAuthnService.beginAuthentication(sessionKey);
        return ResponseEntity.ok(SuccessResponse.of(options));
    }

    // =========================================================================
    // Authentication — Finish
    // =========================================================================

    /**
     * Completes the passkey authentication ceremony and issues a session cookie.
     *
     * On success, sets the same {@code auth_token} (and optionally {@code remember_me})
     * HttpOnly cookies as the PESEL+password login endpoint.
     */
    @PostMapping("/authentication/finish")
    @Operation(summary = "Complete passkey authentication — sets session cookie on success")
    public ResponseEntity<SuccessResponse<String>> authenticationFinish(
            @Valid @RequestBody AuthenticationFinishRequest request,
            HttpServletResponse response) {

        AuthResult result = webAuthnService.finishAuthentication(request);
        applyAuthCookies(response, result, request.rememberMe(), request.clientType());

        return ResponseEntity.ok(SuccessResponse.of("Logged in successfully"));
    }

    // =========================================================================
    // Credential management
    // =========================================================================

    /**
     * Lists all passkeys registered for the authenticated user.
     */
    @GetMapping("/credentials")
    @Operation(summary = "List registered passkeys for the authenticated user")
    public ResponseEntity<SuccessResponse<List<CredentialInfo>>> listCredentials(
            @AuthenticationPrincipal AuthPrincipal principal) {

        requireAuthenticated(principal);

        List<CredentialInfo> credentials = webAuthnService.listCredentials(principal.id())
                .stream()
                .map(CredentialInfo::from)
                .toList();

        return ResponseEntity.ok(SuccessResponse.of(credentials));
    }

    /**
     * Revokes (deletes) a specific passkey by its database ID.
     *
     * Only the owner of the credential can delete it (enforced in the service layer).
     */
    @DeleteMapping("/credentials/{id}")
    @Operation(summary = "Revoke a registered passkey")
    @RequireSudoMode
    public ResponseEntity<SuccessResponse<String>> deleteCredential(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {

        requireAuthenticated(principal);
        webAuthnService.deleteCredential(id, principal.id());
        return ResponseEntity.ok(SuccessResponse.of("Passkey revoked"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void requireAuthenticated(AuthPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
    }

    /**
     * Extracts the raw session token from the request cookie.
     * This value is used as the ceremony session key so it is bound to the active session.
     */
    private String extractSessionKey(HttpServletRequest request) {
        if (request.getCookies() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No session cookie");
        }
        for (var cookie : request.getCookies()) {
            if (AuthTokenFilter.SESSION_COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No session cookie");
    }

    private void applyAuthCookies(HttpServletResponse response, AuthResult result,
                                  boolean rememberMe, com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ClientType clientType) {
        int sessionMaxAge = (int) (sessionTtlMinutes * 60);
        AuthTokenFilter.addCookie(response, AuthTokenFilter.SESSION_COOKIE_NAME,
                result.sessionToken(), sessionMaxAge);

        if (rememberMe && result.rememberMeToken() != null) {
            long days = clientType == com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ClientType.MOBILE
                    ? rememberMeMobileDays : rememberMeWebDays;
            int rememberMaxAge = (int) (days * 24 * 60 * 60);
            AuthTokenFilter.addCookie(response, AuthTokenFilter.REMEMBER_ME_COOKIE_NAME,
                    result.rememberMeToken(), rememberMaxAge);
        }
    }
}
