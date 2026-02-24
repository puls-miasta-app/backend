package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.*;
import com.github.PulsMiastaApp.PulsMiasta.Security.Filter.AuthTokenFilter;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.AuthResult;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.AuthService;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.EmailVerificationService;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.SudoModeService;
import com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.DTO.AuthenticationBeginResponse;
import com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.DTO.AuthenticationFinishRequest;
import com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.Service.WebAuthnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SudoModeService sudoModeService;
    private final WebAuthnService webAuthnService;
    private final EmailVerificationService emailVerificationService;

    @Value("${auth.session.ttl-minutes}")
    private long sessionTtlMinutes;

    @Value("${auth.remember-me.web.ttl-days}")
    private long rememberMeWebDays;

    @Value("${auth.remember-me.mobile.ttl-days}")
    private long rememberMeMobileDays;

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
        applyAuthCookies(response, result, request.rememberMe(), request.clientType());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of("Registered successfully"));
    }

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

    @PostMapping("/login")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = LoginSuccessResponse.class))),
            @ApiResponse(responseCode = "401", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<SuccessResponse<String>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {

        AuthResult result = authService.login(request);
        applyAuthCookies(response, result, request.rememberMe(), request.clientType());

        return ResponseEntity.ok(SuccessResponse.of("Logged in successfully"));
    }

    @PostMapping("/logout")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = LogoutSuccessResponse.class)))
    })
    public ResponseEntity<SuccessResponse<String>> logout(
            HttpServletRequest request,
            HttpServletResponse response) {

        String sessionToken = extractCookie(request, AuthTokenFilter.SESSION_COOKIE_NAME).orElse(null);
        String rememberMeToken = extractCookie(request, AuthTokenFilter.REMEMBER_ME_COOKIE_NAME).orElse(null);

        authService.logout(sessionToken, rememberMeToken);

        AuthTokenFilter.clearCookie(response, AuthTokenFilter.SESSION_COOKIE_NAME);
        AuthTokenFilter.clearCookie(response, AuthTokenFilter.REMEMBER_ME_COOKIE_NAME);
        SecurityContextHolder.clearContext();

        return ResponseEntity.ok(SuccessResponse.of("Logged out successfully"));
    }

    // =========================================================================
    // Sudo Mode endpoints
    // =========================================================================

    /**
     * Checks if the current user has active sudo mode.
     */
    @GetMapping("/sudo/status")
    @Operation(summary = "Check if sudo mode is active")
    @Tag(name = "Authentication")
    public ResponseEntity<SuccessResponse<SudoStatusResponse>> sudoStatus(
            @AuthenticationPrincipal com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal principal,
            HttpServletRequest request) {

        String sessionToken = extractCookie(request, AuthTokenFilter.SESSION_COOKIE_NAME)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));

        boolean isActive = sudoModeService.isSudoModeActive(sessionToken);
        SudoStatusResponse response = new SudoStatusResponse(isActive);

        return ResponseEntity.ok(SuccessResponse.of(response));
    }

    /**
     * Begins sudo mode verification using passkey authentication.
     */
    @PostMapping("/sudo/begin")
    @Operation(summary = "Begin sudo mode verification")
    @Tag(name = "Authentication")
    public ResponseEntity<SuccessResponse<AuthenticationBeginResponse>> sudoBegin(
            @AuthenticationPrincipal com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal principal) {

        String sessionKey = UUID.randomUUID().toString();
        AuthenticationBeginResponse options = webAuthnService.beginAuthentication(sessionKey);
        return ResponseEntity.ok(SuccessResponse.of(options));
    }

    /**
     * Completes sudo mode verification using passkey authentication.
     */
    @PostMapping("/sudo/finish")
    @Operation(summary = "Complete sudo mode verification")
    @Tag(name = "Authentication")
    public ResponseEntity<SuccessResponse<String>> sudoFinish(
            @AuthenticationPrincipal com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal principal,
            @Valid @RequestBody AuthenticationFinishRequest request,
            HttpServletRequest httpRequest) {

        webAuthnService.finishAuthentication(request);

        String sessionToken = extractCookie(httpRequest, AuthTokenFilter.SESSION_COOKIE_NAME)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));

        sudoModeService.activateSudoMode(sessionToken);

        return ResponseEntity.ok(SuccessResponse.of("Sudo mode activated"));
    }

    /**
     * Deactivates sudo mode.
     */
    @PostMapping("/sudo/deactivate")
    @Operation(summary = "Deactivate sudo mode")
    @Tag(name = "Authentication")
    public ResponseEntity<SuccessResponse<String>> sudoDeactivate(
            @AuthenticationPrincipal com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal principal,
            HttpServletRequest request) {

        String sessionToken = extractCookie(request, AuthTokenFilter.SESSION_COOKIE_NAME)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));

        sudoModeService.deactivateSudoMode(sessionToken);
        return ResponseEntity.ok(SuccessResponse.of("Sudo mode deactivated"));
    }

    record SudoStatusResponse(boolean isActive) {
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
        public LoginSuccessResponse() {
            super(true, "Logged in successfully");
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

    private void applyAuthCookies(HttpServletResponse response, AuthResult result,
                                  boolean rememberMe, ClientType clientType) {
        int sessionMaxAge = (int) (sessionTtlMinutes * 60);
        AuthTokenFilter.addCookie(response, AuthTokenFilter.SESSION_COOKIE_NAME,
                result.sessionToken(), sessionMaxAge);

        if (rememberMe && result.rememberMeToken() != null) {
            long days = clientType == ClientType.MOBILE ? rememberMeMobileDays : rememberMeWebDays;
            int rememberMaxAge = (int) (days * 24 * 60 * 60);
            AuthTokenFilter.addCookie(response, AuthTokenFilter.REMEMBER_ME_COOKIE_NAME,
                    result.rememberMeToken(), rememberMaxAge);
        }
    }

    private Optional<String> extractCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return Optional.empty();
        return Arrays.stream(request.getCookies())
                .filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }
}
