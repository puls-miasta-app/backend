package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.*;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Security.Filter.AuthTokenFilter;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.*;
import com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.DTO.AuthenticationBeginResponse;
import com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.DTO.AuthenticationFinishRequest;
import com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.DTO.SudoFinishRequest;
import com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.Service.WebAuthnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SudoModeService sudoModeService;
    private final WebAuthnService webAuthnService;
    private final EmailVerificationService emailVerificationService;
    private final TwoFactorPendingService twoFactorPendingService;
    private final TotpService totpService;
    private final SudoOtpService sudoOtpService;
    private final LoginOtpService loginOtpService;
    private final RateLimitService rateLimitService;

    @Value("${auth.session.ttl-minutes}")
    private long sessionTtlMinutes;

    @Value("${auth.remember-me.web.ttl-days}")
    private long rememberMeWebDays;

    @Value("${auth.remember-me.mobile.ttl-days}")
    private long rememberMeMobileDays;

    // =========================================================================
    // Register
    // =========================================================================

    @PostMapping("/register")
    @ApiResponses({
            @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = RegisterSuccessResponse.class))),
            @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<SuccessResponse<String>> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletResponse response) {

        AuthResult result = authService.register(request);
        AuthTokenFilter.applyAuthCookies(response, result, request.rememberMe(),
                request.clientType() == ClientType.MOBILE,
                sessionTtlMinutes, rememberMeWebDays, rememberMeMobileDays);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of("Registered successfully"));
    }

    // =========================================================================
    // Email verification
    // =========================================================================

    @GetMapping("/verify-email")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = VerifyEmailSuccessResponse.class))),
            @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<SuccessResponse<String>> verifyEmail(@RequestParam String token) {
        emailVerificationService.verifyToken(token);
        return ResponseEntity.ok(SuccessResponse.of("Email verified successfully"));
    }

    // =========================================================================
    // Login (step 1 — password)
    // =========================================================================

    /**
     * First step of login: verify email + password.
     * <p>
     * <b>200 OK</b> — no 2FA configured; session cookies are set immediately.<br>
     * <b>202 Accepted</b> — TOTP required; response body contains {@code pendingToken}.
     * Submit that token + TOTP code to {@code POST /login/totp} to complete login.
     */
    @PostMapping("/login")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = LoginSuccessResponse.class))),
            @ApiResponse(responseCode = "202", description = "TOTP required — see pendingToken in response body"),
            @ApiResponse(responseCode = "401", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    public ResponseEntity<SuccessResponse<?>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response,
            HttpServletRequest httpRequest) {

        rateLimitService.checkRateLimit(httpRequest, 5, Duration.ofMinutes(1));

        String clientIp = rateLimitService.getClientIp(httpRequest);
        LoginResult result = authService.login(request, clientIp);

        return switch (result) {
            case LoginResult.SessionGranted granted -> {
                AuthTokenFilter.applyAuthCookies(response,
                        new AuthResult(granted.sessionToken(), granted.rememberMeToken()),
                        request.rememberMe(), request.clientType() == ClientType.MOBILE,
                        sessionTtlMinutes, rememberMeWebDays, rememberMeMobileDays);
                yield ResponseEntity.ok(SuccessResponse.of(
                        new LoginSuccessResponse(granted.mustChangePassword(), granted.mustSetup2FA())));
            }
            case LoginResult.TwoFactorRequired pending -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(SuccessResponse.of(new TwoFactorRequiredResponse(
                            pending.pendingToken(), pending.availableMethods())));
        };
    }

    // =========================================================================
    // Login (step 2 — TOTP)
    // =========================================================================

    /**
     * Second step of login for accounts with TOTP enabled.
     * <p>
     * Consumes the {@code pendingToken} from the first step and verifies the TOTP code.
     * On success sets session cookies identically to a normal login.
     */
    @PostMapping("/login/totp")
    @Operation(summary = "Complete login with TOTP code (step 2 after 202 from /login)")
    public ResponseEntity<SuccessResponse<String>> loginTotp(
            @Valid @RequestBody LoginTotpRequest request,
            HttpServletResponse response,
            HttpServletRequest httpRequest) {

        rateLimitService.checkRateLimit(httpRequest, 5, Duration.ofMinutes(1));

        Long userId = twoFactorPendingService.validatePendingToken(request.pendingToken());
        requireMethod(request.pendingToken(), "TOTP");
        User user = authService.findById(userId);

        if (!totpService.isValidCode(user.getTotpSecret(), request.totpCode())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid TOTP code");
        }

        twoFactorPendingService.consumePendingToken(request.pendingToken());

        AuthResult result = authService.completeLoginWithSession(userId, request.rememberMe(), request.clientType());
        AuthTokenFilter.applyAuthCookies(response, result, request.rememberMe(),
                request.clientType() == ClientType.MOBILE,
                sessionTtlMinutes, rememberMeWebDays, rememberMeMobileDays);

        return ResponseEntity.ok(SuccessResponse.of("Logged in successfully"));
    }

    // =========================================================================
    // Login (step 2 — Email OTP)
    // =========================================================================

    /**
     * Sends a 6-digit OTP code to the user's email address.
     * Requires a valid {@code pendingToken} from step 1 (non-consuming).
     */
    @PostMapping("/login/otp/send")
    @Operation(summary = "Send email OTP for login 2FA (step 2 after 202 from /login)")
    public ResponseEntity<SuccessResponse<String>> loginOtpSend(
            @Valid @RequestBody PendingTokenRequest request,
            HttpServletRequest httpRequest) {

        rateLimitService.checkRateLimit(httpRequest, 5, Duration.ofMinutes(1));

        Long userId = twoFactorPendingService.validatePendingToken(request.pendingToken());
        requireMethod(request.pendingToken(), "EMAIL_OTP");
        User user = authService.findById(userId);
        loginOtpService.sendOtp(user.getId(), user.getEmail(), user.getFirstName());

        return ResponseEntity.ok(SuccessResponse.of("Verification code sent to " + user.getEmail()));
    }

    /**
     * Verifies the 6-digit OTP code and completes login.
     * Consumes the {@code pendingToken} on success.
     */
    @PostMapping("/login/otp/verify")
    @Operation(summary = "Verify email OTP and complete login (step 2 after 202 from /login)")
    public ResponseEntity<SuccessResponse<String>> loginOtpVerify(
            @Valid @RequestBody LoginOtpVerifyRequest request,
            HttpServletResponse response,
            HttpServletRequest httpRequest) {

        rateLimitService.checkRateLimit(httpRequest, 5, Duration.ofMinutes(1));

        Long userId = twoFactorPendingService.validatePendingToken(request.pendingToken());
        requireMethod(request.pendingToken(), "EMAIL_OTP");
        loginOtpService.verifyOtp(userId, request.code());

        twoFactorPendingService.consumePendingToken(request.pendingToken());

        AuthResult result = authService.completeLoginWithSession(userId, request.rememberMe(), request.clientType());
        AuthTokenFilter.applyAuthCookies(response, result, request.rememberMe(),
                request.clientType() == ClientType.MOBILE,
                sessionTtlMinutes, rememberMeWebDays, rememberMeMobileDays);

        return ResponseEntity.ok(SuccessResponse.of("Logged in successfully"));
    }

    // =========================================================================
    // Login (step 2 — Passkey)
    // =========================================================================

    /**
     * Begins a passkey authentication ceremony scoped to the user from the pending token.
     * Requires a valid {@code pendingToken} from step 1 (non-consuming).
     */
    @PostMapping("/login/passkey/begin")
    @Operation(summary = "Begin passkey verification for login 2FA (step 2 after 202 from /login)")
    public ResponseEntity<SuccessResponse<AuthenticationBeginResponse>> loginPasskeyBegin(
            @Valid @RequestBody PendingTokenRequest request,
            HttpServletRequest httpRequest) {

        rateLimitService.checkRateLimit(httpRequest, 5, Duration.ofMinutes(1));

        Long userId = twoFactorPendingService.validatePendingToken(request.pendingToken());
        requireMethod(request.pendingToken(), "PASSKEY");
        String sessionKey = UUID.randomUUID().toString();
        AuthenticationBeginResponse options = webAuthnService.beginLoginAuthentication(userId, sessionKey);

        return ResponseEntity.ok(SuccessResponse.of(options));
    }

    /**
     * Completes the passkey authentication ceremony and logs in the user.
     * Consumes the {@code pendingToken} on success.
     */
    @PostMapping("/login/passkey/finish")
    @Operation(summary = "Complete passkey verification and login (step 2 after 202 from /login)")
    public ResponseEntity<SuccessResponse<String>> loginPasskeyFinish(
            @Valid @RequestBody LoginPasskeyFinishRequest request,
            HttpServletResponse response,
            HttpServletRequest httpRequest) {

        rateLimitService.checkRateLimit(httpRequest, 5, Duration.ofMinutes(1));

        Long userId = twoFactorPendingService.validatePendingToken(request.pendingToken());
        requireMethod(request.pendingToken(), "PASSKEY");

        AuthenticationFinishRequest authFinishRequest = new AuthenticationFinishRequest(
                request.sessionKey(), request.id(), request.rawId(), request.type(),
                request.response(), request.rememberMe(), request.clientType());

        webAuthnService.verifyForLogin(authFinishRequest, userId);

        twoFactorPendingService.consumePendingToken(request.pendingToken());

        AuthResult result = authService.completeLoginWithSession(userId, request.rememberMe(), request.clientType());
        AuthTokenFilter.applyAuthCookies(response, result, request.rememberMe(),
                request.clientType() == ClientType.MOBILE,
                sessionTtlMinutes, rememberMeWebDays, rememberMeMobileDays);

        return ResponseEntity.ok(SuccessResponse.of("Logged in successfully"));
    }

    // =========================================================================
    // Logout
    // =========================================================================

    @PostMapping("/logout")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = LogoutSuccessResponse.class)))
    })
    public ResponseEntity<SuccessResponse<String>> logout(
            HttpServletRequest request,
            HttpServletResponse response) {

        String sessionToken = AuthTokenFilter.extractCookie(request, AuthTokenFilter.SESSION_COOKIE_NAME).orElse(null);
        String rememberMeToken = AuthTokenFilter.extractCookie(request, AuthTokenFilter.REMEMBER_ME_COOKIE_NAME).orElse(null);

        authService.logout(sessionToken, rememberMeToken);

        AuthTokenFilter.clearCookie(response, AuthTokenFilter.SESSION_COOKIE_NAME);
        AuthTokenFilter.clearCookie(response, AuthTokenFilter.REMEMBER_ME_COOKIE_NAME);
        SecurityContextHolder.clearContext();

        return ResponseEntity.ok(SuccessResponse.of("Logged out successfully"));
    }

    // =========================================================================
    // Change password
    // =========================================================================

    /**
     * Zmiana hasła.
     * - Gdy konto ma mustChangePassword=true (ustawione przez admina): currentPassword nie jest wymagane.
     * - W pozostałych przypadkach: currentPassword jest wymagane.
     */
    @PostMapping("/change-password")
    public ResponseEntity<SuccessResponse<String>> changePassword(
            @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (request.newPassword() == null || request.newPassword().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "newPassword must be at least 8 characters");
        }
        authService.changePassword(principal.id(), request.currentPassword(), request.newPassword());
        return ResponseEntity.ok(SuccessResponse.of("Password changed successfully"));
    }

    // =========================================================================
    // 2FA methods
    // =========================================================================

    /**
     * Returns the 2FA methods that are currently active for the authenticated user.
     */
    @GetMapping("/2fa/methods")
    @Operation(summary = "Get active 2FA methods for the authenticated user")
    @Tag(name = "Authentication")
    public ResponseEntity<SuccessResponse<TwoFactorMethodsResponse>> twoFactorMethods(
            @AuthenticationPrincipal AuthPrincipal principal) {

        User user = loadUser(principal);
        int passkeysCount = webAuthnService.listCredentials(principal.id()).size();

        return ResponseEntity.ok(SuccessResponse.of(
                new TwoFactorMethodsResponse(user.isTotpEnabled(), user.isEmailOtpEnabled(), passkeysCount,
                        user.getTwoFactorDefaultMethod())));
    }

    /**
     * Ustawia domyślną metodę 2FA — wyświetlaną jako pierwsza podczas logowania.
     * Podana metoda musi być aktualnie włączona na koncie użytkownika.
     */
    @PutMapping("/2fa/default")
    @Operation(summary = "Set the preferred default 2FA method for login")
    @Tag(name = "Authentication")
    public ResponseEntity<SuccessResponse<String>> setTwoFactorDefault(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody SetDefaultMethodRequest request) {

        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        authService.setTwoFactorDefaultMethod(principal.id(), request.method());
        return ResponseEntity.ok(SuccessResponse.of("Default 2FA method updated"));
    }

    // =========================================================================
    // Sudo Mode — Passkey
    // =========================================================================

    /**
     * Checks if the current user has active sudo mode.
     */
    @GetMapping("/sudo/status")
    @Operation(summary = "Check if sudo mode is active")
    @Tag(name = "Authentication")
    public ResponseEntity<SuccessResponse<SudoStatusResponse>> sudoStatus(
            HttpServletRequest request) {

        String sessionToken = AuthTokenFilter.extractCookie(request, AuthTokenFilter.SESSION_COOKIE_NAME)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));

        boolean isActive = sudoModeService.isSudoModeActive(sessionToken);
        return ResponseEntity.ok(SuccessResponse.of(new SudoStatusResponse(isActive)));
    }

    /**
     * Begins sudo mode verification using passkey authentication.
     * Returns a challenge scoped only to the authenticated user's registered passkeys.
     */
    @PostMapping("/sudo/begin")
    @Operation(summary = "Begin sudo mode verification via passkey")
    @Tag(name = "Authentication")
    public ResponseEntity<SuccessResponse<AuthenticationBeginResponse>> sudoBegin(
            @AuthenticationPrincipal AuthPrincipal principal) {

        String sessionKey = UUID.randomUUID().toString();
        AuthenticationBeginResponse options = webAuthnService.beginSudoAuthentication(principal.id(), sessionKey);
        return ResponseEntity.ok(SuccessResponse.of(options));
    }

    /**
     * Completes sudo mode verification using passkey authentication.
     * Verifies the WebAuthn assertion and confirms the passkey belongs to the authenticated user.
     * Does NOT create a new session — only elevates the existing session to sudo mode.
     */
    @PostMapping("/sudo/finish")
    @Operation(summary = "Complete sudo mode verification via passkey")
    @Tag(name = "Authentication")
    public ResponseEntity<SuccessResponse<String>> sudoFinish(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody SudoFinishRequest request,
            HttpServletRequest httpRequest) {

        webAuthnService.verifyForSudoMode(request, principal.id());
        activateSudoForSession(httpRequest);
        return ResponseEntity.ok(SuccessResponse.of("Sudo mode activated"));
    }

    // =========================================================================
    // Sudo Mode — Email OTP
    // =========================================================================

    /**
     * Sends a 6-digit one-time code to the authenticated user's email address.
     * Subject to a per-user cooldown (default 60 s) to prevent flooding.
     */
    @PostMapping("/sudo/otp/send")
    @Operation(summary = "Send email OTP for sudo mode activation")
    @Tag(name = "Authentication")
    public ResponseEntity<SuccessResponse<String>> sudoOtpSend(
            @AuthenticationPrincipal AuthPrincipal principal) {

        User user = loadUser(principal);
        sudoOtpService.sendOtp(user.getId(), user.getEmail(), user.getFirstName());
        return ResponseEntity.ok(SuccessResponse.of("Verification code sent to " + user.getEmail()));
    }

    /**
     * Verifies the 6-digit OTP and activates sudo mode on success.
     * Max 3 attempts per code; request a new code after exhausting attempts.
     */
    @PostMapping("/sudo/otp/verify")
    @Operation(summary = "Verify email OTP and activate sudo mode")
    @Tag(name = "Authentication")
    public ResponseEntity<SuccessResponse<String>> sudoOtpVerify(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody OtpVerifyRequest request,
            HttpServletRequest httpRequest) {

        sudoOtpService.verifyOtp(principal.id(), request.code());
        activateSudoForSession(httpRequest);
        return ResponseEntity.ok(SuccessResponse.of("Sudo mode activated"));
    }

    // =========================================================================
    // Sudo Mode — TOTP
    // =========================================================================

    /**
     * Verifies a TOTP code from the user's authenticator app and activates sudo mode.
     * Requires TOTP to be enabled on the account ({@code totpEnabled = true}).
     */
    @PostMapping("/sudo/totp/verify")
    @Operation(summary = "Verify TOTP code and activate sudo mode")
    @Tag(name = "Authentication")
    public ResponseEntity<SuccessResponse<String>> sudoTotpVerify(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody OtpVerifyRequest request,
            HttpServletRequest httpRequest) {

        User user = loadUser(principal);

        if (!user.isTotpEnabled() || user.getTotpSecret() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "TOTP is not configured for this account");
        }

        if (!totpService.isValidCode(user.getTotpSecret(), request.code())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid TOTP code");
        }

        activateSudoForSession(httpRequest);
        return ResponseEntity.ok(SuccessResponse.of("Sudo mode activated"));
    }

    // =========================================================================
    // Sudo Mode — Deactivate
    // =========================================================================

    @PostMapping("/sudo/deactivate")
    @Operation(summary = "Deactivate sudo mode")
    @Tag(name = "Authentication")
    public ResponseEntity<SuccessResponse<String>> sudoDeactivate(HttpServletRequest request) {

        String sessionToken = AuthTokenFilter.extractCookie(request, AuthTokenFilter.SESSION_COOKIE_NAME)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));

        sudoModeService.deactivateSudoMode(sessionToken);
        return ResponseEntity.ok(SuccessResponse.of("Sudo mode deactivated"));
    }

    // =========================================================================
    // DTOs
    // =========================================================================

    record TwoFactorMethodsResponse(boolean totpEnabled, boolean emailOtpEnabled, int passkeysCount,
                                    String defaultMethod) {
    }

    record SudoStatusResponse(boolean isActive) {
    }

    record TwoFactorRequiredResponse(String pendingToken, List<String> availableMethods) {
    }

    record PendingTokenRequest(
            @NotBlank String pendingToken
    ) {
    }

    record LoginOtpVerifyRequest(
            @NotBlank String pendingToken,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "Code must be exactly 6 digits") String code,
            boolean rememberMe,
            ClientType clientType
    ) {
    }

    record LoginPasskeyFinishRequest(
            @NotBlank String pendingToken,
            @NotBlank String sessionKey,
            @NotBlank String id,
            @NotBlank String rawId,
            @NotBlank String type,
            @jakarta.validation.constraints.NotNull AuthenticationFinishRequest.AssertionResponse response,
            boolean rememberMe,
            @jakarta.validation.constraints.NotNull ClientType clientType
    ) {
    }

    record LoginTotpRequest(
            @NotBlank String pendingToken,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "TOTP code must be exactly 6 digits") String totpCode,
            boolean rememberMe,
            ClientType clientType
    ) {
    }

    record OtpVerifyRequest(
            @NotBlank @Pattern(regexp = "\\d{6}", message = "Code must be exactly 6 digits") String code
    ) {
    }

    record SetDefaultMethodRequest(
            @NotBlank @Pattern(regexp = "TOTP|EMAIL_OTP|PASSKEY", message = "method must be TOTP, EMAIL_OTP or PASSKEY")
            String method
    ) {
    }

    // -------------------------------------------------------------------------
    // OpenAPI schema helpers — concrete types so springdoc resolves T correctly
    // -------------------------------------------------------------------------

    @Schema(name = "RegisterSuccessResponse")
    private static class RegisterSuccessResponse extends SuccessResponse<String> {
        public RegisterSuccessResponse() {
            super(true, "Registered successfully");
        }
    }

    @Schema(name = "LoginSuccessResponse")
    private static class LoginSuccessResponse extends SuccessResponse<String> {
        @lombok.Getter
        private final boolean mustChangePassword;
        /** True when the user is an admin with no 2FA method configured. Frontend should redirect to 2FA setup. */
        @lombok.Getter
        private final boolean mustSetup2FA;

        public LoginSuccessResponse() {
            super(true, "Logged in successfully");
            this.mustChangePassword = false;
            this.mustSetup2FA = false;
        }

        public LoginSuccessResponse(boolean mustChangePassword, boolean mustSetup2FA) {
            super(true, "Logged in successfully");
            this.mustChangePassword = mustChangePassword;
            this.mustSetup2FA = mustSetup2FA;
        }
    }

    @Schema(name = "LogoutSuccessResponse")
    private static class LogoutSuccessResponse extends SuccessResponse<String> {
        public LogoutSuccessResponse() {
            super(true, "Logged out successfully");
        }
    }

    @Schema(name = "VerifyEmailSuccessResponse")
    private static class VerifyEmailSuccessResponse extends SuccessResponse<String> {
        public VerifyEmailSuccessResponse() {
            super(true, "Email verified successfully");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private User loadUser(AuthPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authService.findById(principal.id());
    }

    private void requireMethod(String pendingToken, String method) {
        List<String> methods = twoFactorPendingService.getAvailableMethods(pendingToken);
        if (!methods.contains(method)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    method + " is not available for this account");
        }
    }

    /**
     * Extracts the session token from the cookie and activates sudo mode for it.
     */
    private void activateSudoForSession(HttpServletRequest request) {
        String sessionToken = AuthTokenFilter.extractCookie(request, AuthTokenFilter.SESSION_COOKIE_NAME)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
        sudoModeService.activateSudoMode(sessionToken);
    }
}
