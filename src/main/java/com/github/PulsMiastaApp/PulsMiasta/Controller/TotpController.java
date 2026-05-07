package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import com.github.PulsMiastaApp.PulsMiasta.Security.Annotation.RequireSudoMode;
import com.github.PulsMiastaApp.PulsMiasta.Security.Filter.AuthTokenFilter;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.AuthService;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.TotpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

/**
 * Manages TOTP (Time-based One-Time Password) 2FA setup and deactivation.
 *
 * <h2>Setup flow</h2>
 * <pre>
 *   POST /v1/auth/totp/setup    ← returns otpauth:// URI (scan with authenticator app)
 *   POST /v1/auth/totp/confirm  ← verify first code → TOTP enabled on account
 * </pre>
 *
 * <h2>Deactivation</h2>
 * <pre>
 *   DELETE /v1/auth/totp  ← requires active sudo mode
 * </pre>
 *
 * <p>The frontend is responsible for rendering the QR code from the returned
 * {@code otpAuthUri} using any standard library (e.g. {@code qrcode.js}).
 */
@RestController
@RequestMapping("/v1/auth/totp")
@RequiredArgsConstructor
@Tag(name = "TOTP 2FA", description = "Time-based One-Time Password two-factor authentication setup and management")
public class TotpController {

    private static final String SETUP_PREFIX = "totp_setup:";

    private final TotpService totpService;
    private final AuthService authService;
    private final UserRepository userRepository;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${auth.totp.setup-ttl-minutes:10}")
    private long setupTtlMinutes;

    /**
     * Generates a new TOTP secret and returns the {@code otpauth://} URI.
     * The secret is stored temporarily in Redis; it is NOT saved to the database
     * until confirmed via {@code POST /confirm}.
     *
     * <p>The client should use the URI to display a QR code for the user to scan
     * with their authenticator app.
     */
    @PostMapping("/setup")
    @RequireSudoMode
    @Operation(summary = "Begin TOTP setup — returns otpauth:// URI for QR code (requires sudo mode)")
    public ResponseEntity<SuccessResponse<TotpSetupResponse>> setup(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request) {

        User user = loadUser(principal);
        String sessionToken = extractSessionToken(request);

        String secret = totpService.generateSecret();
        stringRedisTemplate.opsForValue().set(
                SETUP_PREFIX + sessionToken, secret, Duration.ofMinutes(setupTtlMinutes));

        String uri = totpService.buildOtpAuthUri(user.getEmail(), secret);
        return ResponseEntity.ok(SuccessResponse.of(new TotpSetupResponse(uri)));
    }

    /**
     * Confirms the TOTP setup by verifying the first code from the authenticator app.
     * On success, saves the secret to the user account and enables TOTP.
     */
    @PostMapping("/confirm")
    @RequireSudoMode
    @Operation(summary = "Confirm TOTP setup with first code — enables TOTP on account (requires sudo mode)")
    public ResponseEntity<SuccessResponse<String>> confirm(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody TotpCodeRequest body,
            HttpServletRequest request) {

        User user = loadUser(principal);
        String sessionToken = extractSessionToken(request);

        String secret = stringRedisTemplate.opsForValue().get(SETUP_PREFIX + sessionToken);
        if (secret == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "TOTP setup session expired. Please start setup again.");
        }

        if (!totpService.isValidCode(secret, body.code())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid TOTP code. Make sure the time on your device is correct.");
        }

        user.setTotpSecret(secret);
        user.setTotpEnabled(true);
        userRepository.save(user);

        stringRedisTemplate.delete(SETUP_PREFIX + sessionToken);

        return ResponseEntity.ok(SuccessResponse.of("TOTP two-factor authentication enabled"));
    }

    /**
     * Disables TOTP for the authenticated user.
     * Requires active sudo mode to prevent accidental or unauthorized disablement.
     */
    @DeleteMapping
    @RequireSudoMode
    @Operation(summary = "Disable TOTP — requires sudo mode")
    public ResponseEntity<SuccessResponse<String>> disable(
            @AuthenticationPrincipal AuthPrincipal principal) {

        User user = loadUser(principal);
        user.setTotpEnabled(false);
        user.setTotpSecret(null);
        if ("TOTP".equals(user.getTwoFactorDefaultMethod())) {
            user.setTwoFactorDefaultMethod(null);
        }
        userRepository.save(user);

        return ResponseEntity.ok(SuccessResponse.of("TOTP two-factor authentication disabled"));
    }

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    record TotpSetupResponse(String otpAuthUri) {
    }

    record TotpCodeRequest(
            @NotBlank
            @Pattern(regexp = "\\d{6}", message = "TOTP code must be exactly 6 digits")
            String code
    ) {
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

    private String extractSessionToken(HttpServletRequest request) {
        return AuthTokenFilter.extractCookie(request, AuthTokenFilter.SESSION_COOKIE_NAME)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No session cookie"));
    }
}
