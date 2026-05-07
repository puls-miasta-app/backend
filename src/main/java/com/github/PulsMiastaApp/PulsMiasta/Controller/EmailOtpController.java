package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import com.github.PulsMiastaApp.PulsMiasta.Security.Annotation.RequireSudoMode;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Zarządza włączaniem i wyłączaniem Email OTP jako metody 2FA.
 *
 * <h2>Włączenie</h2>
 * <pre>POST /v1/auth/email-otp/enable  — wymaga sudo mode</pre>
 *
 * <h2>Wyłączenie</h2>
 * <pre>DELETE /v1/auth/email-otp       — wymaga sudo mode; czyści domyślną metodę jeśli była EMAIL_OTP</pre>
 *
 * <p>Admini mają Email OTP włączone od razu po założeniu konta — mogą je zastąpić
 * TOTP lub passkey, ale muszą mieć przynajmniej jedną metodę 2FA.
 */
@RestController
@RequestMapping("/v1/auth/email-otp")
@RequiredArgsConstructor
@Tag(name = "Email OTP 2FA", description = "Email one-time password 2FA management")
public class EmailOtpController {

    private final AuthService authService;
    private final UserRepository userRepository;

    /**
     * Włącza Email OTP na koncie użytkownika.
     * Nie wymaga żadnej konfiguracji — kod jest wysyłany na adres e-mail powiązany z kontem.
     */
    @PostMapping("/enable")
    @RequireSudoMode
    @Operation(summary = "Enable Email OTP 2FA (requires sudo mode)")
    public ResponseEntity<SuccessResponse<String>> enable(
            @AuthenticationPrincipal AuthPrincipal principal) {

        User user = loadUser(principal);
        user.setEmailOtpEnabled(true);
        userRepository.save(user);
        return ResponseEntity.ok(SuccessResponse.of("Email OTP two-factor authentication enabled"));
    }

    /**
     * Wyłącza Email OTP na koncie użytkownika.
     * Jeśli Email OTP była ustawioną domyślną metodą, domyślna metoda zostaje wyczyszczona.
     */
    @DeleteMapping
    @RequireSudoMode
    @Operation(summary = "Disable Email OTP 2FA (requires sudo mode)")
    public ResponseEntity<SuccessResponse<String>> disable(
            @AuthenticationPrincipal AuthPrincipal principal) {

        User user = loadUser(principal);
        user.setEmailOtpEnabled(false);
        if ("EMAIL_OTP".equals(user.getTwoFactorDefaultMethod())) {
            user.setTwoFactorDefaultMethod(null);
        }
        userRepository.save(user);
        return ResponseEntity.ok(SuccessResponse.of("Email OTP two-factor authentication disabled"));
    }

    private User loadUser(AuthPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return authService.findById(principal.id());
    }
}
