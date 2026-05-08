package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.*;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Security.Annotation.RequireSudoMode;
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
import java.util.ArrayList;
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
     *
     * <b>200 OK</b> — no 2FA configured; session cookies set immediately; sudo mode auto-activated.<br>
     * <b>202 Accepted</b> — 2FA required; response contains {@code pendingToken}.
     * Submit that token to the relevant step-2 endpoint to complete login.
     * Sudo mode is auto-activated on step-2 completion.
     */
    @PostMapping("/login")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = LoginSuccessResponse.class))),
            @ApiResponse(responseCode = "202", description = "2FA required — see pendingToken and availableMethods in response body"),
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
                // Auto-activate sudo mode immediately after login (no 2FA path)
                sudoModeService.activateSudoMode(granted.sessionToken());
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
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Nieprawidłowy kod TOTP");
        }

        twoFactorPendingService.consumePendingToken(request.pendingToken());

        AuthResult result = authService.completeLoginWithSession(userId, request.rememberMe(), request.clientType());
        AuthTokenFilter.applyAuthCookies(response, result, request.rememberMe(),
                request.clientType() == ClientType.MOBILE,
                sessionTtlMinutes, rememberMeWebDays, rememberMeMobileDays);
        sudoModeService.activateSudoMode(result.sessionToken());

        return ResponseEntity.ok(SuccessResponse.of("Logged in successfully"));
    }

    // =========================================================================
    // Login (step 2 — Email OTP)
    // =========================================================================

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
        sudoModeService.activateSudoMode(result.sessionToken());

        return ResponseEntity.ok(SuccessResponse.of("Logged in successfully"));
    }

    // =========================================================================
    // Login (step 2 — Passkey)
    // =========================================================================

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
        sudoModeService.activateSudoMode(result.sessionToken());

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

        if (sessionToken != null) {
            sudoModeService.deactivateSudoMode(sessionToken);
        }
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
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wymagane uwierzytelnienie");
        }
        if (request.newPassword() == null || request.newPassword().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nowe hasło musi mieć co najmniej 8 znaków");
        }
        authService.changePassword(principal.id(), request.currentPassword(), request.newPassword());
        return ResponseEntity.ok(SuccessResponse.of("Password changed successfully"));
    }

    // =========================================================================
    // 2FA methods
    // =========================================================================

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

    @PutMapping("/2fa/default")
    @RequireSudoMode
    @Operation(summary = "Set the preferred default 2FA method for login (requires sudo mode)")
    @Tag(name = "Authentication")
    public ResponseEntity<SuccessResponse<String>> setTwoFactorDefault(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody SetDefaultMethodRequest request) {

        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wymagane uwierzytelnienie");
        }
        authService.setTwoFactorDefaultMethod(principal.id(), request.method());
        return ResponseEntity.ok(SuccessResponse.of("Default 2FA method updated"));
    }

    // =========================================================================
    // Sudo Mode — status & available methods
    // =========================================================================

    /**
     * Returns sudo mode status for the current session.
     * Includes remaining TTL (in seconds) while active.
     */
    @GetMapping("/sudo/status")
    @Operation(
            summary = "Get sudo mode status",
            description = "Returns whether sudo mode is currently active and, if so, how many seconds remain. " +
                    "Sudo mode is automatically activated after successful login and expires after " +
                    "${auth.sudo.ttl-minutes} minutes of inactivity. It must be re-activated before " +
                    "performing any sensitive account operation.")
    @Tag(name = "Sudo Mode")
    public ResponseEntity<SuccessResponse<SudoStatusResponse>> sudoStatus(
            HttpServletRequest request) {

        String sessionToken = AuthTokenFilter.extractCookie(request, AuthTokenFilter.SESSION_COOKIE_NAME)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));

        SudoModeService.SudoStatus status = sudoModeService.getStatus(sessionToken);
        return ResponseEntity.ok(SuccessResponse.of(new SudoStatusResponse(status.isActive(), status.remainingSeconds())));
    }

    /**
     * Lists available methods the user can use to activate sudo mode.
     * EMAIL_OTP is always listed as it is the universal fallback — no prior setup required.
     * TOTP and PASSKEY appear only when enabled on the account.
     */
    @GetMapping("/sudo/available-methods")
    @Operation(
            summary = "List available sudo mode verification methods",
            description = "Returns ordered list of methods the user can use to activate sudo mode. " +
                    "EMAIL_OTP is always available as the default fallback. " +
                    "TOTP and PASSKEY appear when configured on the account.")
    @Tag(name = "Sudo Mode")
    public ResponseEntity<SuccessResponse<SudoAvailableMethodsResponse>> sudoAvailableMethods(
            @AuthenticationPrincipal AuthPrincipal principal) {

        User user = loadUser(principal);
        List<String> methods = buildSudoAvailableMethods(user, principal.id());
        return ResponseEntity.ok(SuccessResponse.of(new SudoAvailableMethodsResponse(methods)));
    }

    // =========================================================================
    // Sudo Mode — Passkey
    // =========================================================================

    @PostMapping("/sudo/begin")
    @Operation(
            summary = "Begin sudo mode verification via passkey",
            description = "Returns a WebAuthn challenge scoped to the authenticated user's passkeys. " +
                    "Pass the challenge to navigator.credentials.get() and send the result to /sudo/finish.")
    @Tag(name = "Sudo Mode")
    public ResponseEntity<SuccessResponse<AuthenticationBeginResponse>> sudoBegin(
            @AuthenticationPrincipal AuthPrincipal principal) {

        String sessionKey = UUID.randomUUID().toString();
        AuthenticationBeginResponse options = webAuthnService.beginSudoAuthentication(principal.id(), sessionKey);
        return ResponseEntity.ok(SuccessResponse.of(options));
    }

    @PostMapping("/sudo/finish")
    @Operation(
            summary = "Complete sudo mode verification via passkey",
            description = "Verifies the WebAuthn assertion. On success activates sudo mode for this session.")
    @Tag(name = "Sudo Mode")
    public ResponseEntity<SuccessResponse<SudoActivatedResponse>> sudoFinish(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody SudoFinishRequest request,
            HttpServletRequest httpRequest) {

        webAuthnService.verifyForSudoMode(request, principal.id());
        String sessionToken = activateSudoForSession(httpRequest);
        long remaining = sudoModeService.getRemainingTtlSeconds(sessionToken);
        return ResponseEntity.ok(SuccessResponse.of(new SudoActivatedResponse("Sudo mode activated", remaining)));
    }

    // =========================================================================
    // Sudo Mode — Email OTP
    // =========================================================================

    /**
     * Sends a 6-digit verification code to the user's email.
     * Available to all authenticated users — no prior email OTP setup required.
     * Subject to per-user cooldown (default 60 s).
     */
    @PostMapping("/sudo/otp/send")
    @Operation(
            summary = "Send email OTP for sudo mode activation",
            description = "Sends a 6-digit code to the account email address. No prior setup required — " +
                    "email OTP is the universal fallback sudo method. Subject to a 60-second cooldown.")
    @Tag(name = "Sudo Mode")
    public ResponseEntity<SuccessResponse<String>> sudoOtpSend(
            @AuthenticationPrincipal AuthPrincipal principal) {

        User user = loadUser(principal);
        sudoOtpService.sendOtp(user.getId(), user.getEmail(), user.getFirstName());
        return ResponseEntity.ok(SuccessResponse.of("Verification code sent to " + user.getEmail()));
    }

    /**
     * Verifies the 6-digit OTP and activates sudo mode.
     * Max 3 attempts per code; request a new code after exhausting attempts.
     */
    @PostMapping("/sudo/otp/verify")
    @Operation(
            summary = "Verify email OTP and activate sudo mode",
            description = "Verifies the code sent by /sudo/otp/send. On success activates sudo mode " +
                    "for ${auth.sudo.ttl-minutes} minutes. Returns remaining TTL in seconds.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sudo mode activated"),
            @ApiResponse(responseCode = "401", description = "Invalid or expired code"),
            @ApiResponse(responseCode = "429", description = "Too many attempts — request a new code")
    })
    @Tag(name = "Sudo Mode")
    public ResponseEntity<SuccessResponse<SudoActivatedResponse>> sudoOtpVerify(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody OtpVerifyRequest request,
            HttpServletRequest httpRequest) {

        sudoOtpService.verifyOtp(principal.id(), request.code());
        String sessionToken = activateSudoForSession(httpRequest);
        long remaining = sudoModeService.getRemainingTtlSeconds(sessionToken);
        return ResponseEntity.ok(SuccessResponse.of(new SudoActivatedResponse("Sudo mode activated", remaining)));
    }

    // =========================================================================
    // Sudo Mode — TOTP
    // =========================================================================

    @PostMapping("/sudo/totp/verify")
    @Operation(
            summary = "Verify TOTP code and activate sudo mode",
            description = "Requires TOTP to be enabled on the account. On success activates sudo mode " +
                    "for ${auth.sudo.ttl-minutes} minutes. Returns remaining TTL in seconds.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sudo mode activated"),
            @ApiResponse(responseCode = "400", description = "TOTP not configured on account"),
            @ApiResponse(responseCode = "401", description = "Invalid TOTP code")
    })
    @Tag(name = "Sudo Mode")
    public ResponseEntity<SuccessResponse<SudoActivatedResponse>> sudoTotpVerify(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody OtpVerifyRequest request,
            HttpServletRequest httpRequest) {

        rateLimitService.checkRateLimit(httpRequest, 5, Duration.ofMinutes(1));

        User user = loadUser(principal);

        if (!user.isTotpEnabled() || user.getTotpSecret() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "TOTP nie jest skonfigurowany dla tego konta");
        }

        if (!totpService.isValidCode(user.getTotpSecret(), request.code())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Nieprawidłowy kod TOTP");
        }

        String sessionToken = activateSudoForSession(httpRequest);
        long remaining = sudoModeService.getRemainingTtlSeconds(sessionToken);
        return ResponseEntity.ok(SuccessResponse.of(new SudoActivatedResponse("Sudo mode activated", remaining)));
    }

    // =========================================================================
    // Sudo Mode — Deactivate
    // =========================================================================

    @PostMapping("/sudo/deactivate")
    @Operation(
            summary = "Deactivate sudo mode",
            description = "Immediately revokes elevated privileges for this session. " +
                    "The session itself remains valid.")
    @Tag(name = "Sudo Mode")
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

    record SudoStatusResponse(
            @Schema(description = "Whether sudo mode is currently active for this session")
            boolean isActive,
            @Schema(description = "Seconds remaining until sudo expires (0 when inactive)")
            long remainingSeconds
    ) {
    }

    record SudoActivatedResponse(
            @Schema(description = "Human-readable confirmation message")
            String message,
            @Schema(description = "Seconds until sudo mode expires")
            long remainingSeconds
    ) {
    }

    record SudoAvailableMethodsResponse(
            @Schema(description = "Ordered list of methods available for sudo activation. " +
                    "EMAIL_OTP is always present as the universal fallback.")
            List<String> methods
    ) {
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
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wymagane uwierzytelnienie");
        }
        return authService.findById(principal.id());
    }

    private void requireMethod(String pendingToken, String method) {
        List<String> methods = twoFactorPendingService.getAvailableMethods(pendingToken);
        if (!methods.contains(method)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    method + " nie jest dostępna dla tego konta");
        }
    }

    /**
     * Extracts the session token from the cookie, activates sudo mode, and returns the token.
     */
    private String activateSudoForSession(HttpServletRequest request) {
        String sessionToken = AuthTokenFilter.extractCookie(request, AuthTokenFilter.SESSION_COOKIE_NAME)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
        sudoModeService.activateSudoMode(sessionToken);
        return sessionToken;
    }

    /**
     * Builds an ordered list of methods available for sudo activation.
     * TOTP and PASSKEY appear first when configured; EMAIL_OTP is always the final fallback.
     */
    private List<String> buildSudoAvailableMethods(User user, Long userId) {
        List<String> methods = new ArrayList<>();
        if (user.isTotpEnabled()) methods.add("TOTP");
        if (!webAuthnService.listCredentials(userId).isEmpty()) methods.add("PASSKEY");
        methods.add("EMAIL_OTP");
        return methods;
    }
}
