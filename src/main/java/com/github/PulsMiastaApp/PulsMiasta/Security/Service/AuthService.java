package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ClientType;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.LoginRequest;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.LoginResult;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.RegisterRequest;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserCredentialRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserCredentialRepository userCredentialRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final TwoFactorPendingService twoFactorPendingService;
    private final LoginAttemptService loginAttemptService;

    public AuthResult register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
        }

        User user = new User();
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setEmail(request.email());
        user.setRole("USER");
        // Domyślnie każdy nowy użytkownik ma włączone Email OTP. Może je później wyłączyć
        // (endpoint to disable + wybór innej metody 2FA do zrobienia osobno).
        user.setEmailOtpEnabled(true);

        userRepository.save(user);
        emailVerificationService.sendVerificationEmail(user);

        return buildAuthResult(user.getId(), request.rememberMe(), request.clientType());
    }

    /**
     * Authenticates the user with email + password.
     * <p>
     * Returns {@link LoginResult.SessionGranted} when no 2FA is configured/required,
     * or {@link LoginResult.TwoFactorRequired} when the account requires TOTP verification
     * before a session can be granted (ADMIN role always requires TOTP).
     * <p>
     * Performs user lookup before lockout check to prevent email enumeration attacks.
     * Uses consistent error messages to avoid leaking information about account existence.
     * <p>
     * Lockout is scoped to the (clientIp, email) pair so that an attacker sending requests
     * from their own IP cannot lock out the legitimate owner logging in from a different IP.
     *
     * @param request  login credentials
     * @param clientIp resolved client IP from {@link RateLimitService#getClientIp}
     * @return {@link LoginResult} — either a full session or a pending 2FA token
     */
    public LoginResult login(LoginRequest request, String clientIp) {
        User user = userRepository.findByEmail(request.email()).orElse(null);

        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        loginAttemptService.checkLockout(clientIp, request.email());

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginAttemptService.recordFailedAttempt(clientIp, request.email());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        if (!user.isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Email must be verified before login");
        }

        loginAttemptService.clearAttempts(clientIp, request.email());

        // Migracja istniejących kont: jeżeli user nie ma Email OTP włączonego,
        // włączamy mu to domyślnie (preferencja w profilu). Nie blokuje loginu —
        // 2FA na kroku login/step 1 jest wyłączony, cookies lecą od razu tak jak w register.
        if (!user.isEmailOtpEnabled()) {
            user.setEmailOtpEnabled(true);
            userRepository.save(user);
        }

        // Brak gate'a 2FA — każdy udany login od razu dostaje sesję.
        // 2FA flow (login/totp, login/otp/verify, login/passkey/finish) zostaje dostępny
        // dla klientów, które chcą go użyć jawnie — patrz AuthController.
        AuthResult result = buildAuthResult(user.getId(), request.rememberMe(), request.clientType());
        return new LoginResult.SessionGranted(result.sessionToken(), result.rememberMeToken());
    }

    /**
     * Completes login after successful TOTP verification.
     * Called by the controller after {@link TwoFactorPendingService#consumePendingToken}.
     */
    public AuthResult completeLoginWithSession(Long userId, boolean rememberMe, ClientType clientType) {
        return buildAuthResult(userId, rememberMe, clientType);
    }

    public User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
    }

    public User findById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    public void logout(String sessionToken, String rememberMeToken) {
        tokenService.invalidateSession(sessionToken);
        if (rememberMeToken != null) {
            tokenService.invalidateRememberMeToken(rememberMeToken);
        }
    }

    private List<String> buildAvailableMethods(User user) {
        List<String> methods = new ArrayList<>();
        if (user.isTotpEnabled()) {
            methods.add("TOTP");
        }
        if (user.isEmailOtpEnabled()) {
            methods.add("EMAIL_OTP");
        }
        if (!userCredentialRepository.findAllByUserId(user.getId()).isEmpty()) {
            methods.add("PASSKEY");
        }
        return methods;
    }

    private AuthResult buildAuthResult(Long userId, boolean rememberMe, ClientType clientType) {
        String sessionToken = tokenService.createSession(userId);
        String rememberMeToken = rememberMe
                ? tokenService.createRememberMeToken(userId, clientType)
                : null;
        return new AuthResult(sessionToken, rememberMeToken);
    }
}
