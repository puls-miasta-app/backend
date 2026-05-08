package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ClientType;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.LoginRequest;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.LoginResult;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.RegisterRequest;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.UserRole;
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
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Adres e-mail jest już używany");
        }

        User user = new User();
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setEmail(request.email());
        user.setRole("USER");
        // 2FA domyślnie wyłączone — user włącza wybraną metodę w ustawieniach konta.

        userRepository.save(user);
        emailVerificationService.sendVerificationEmail(user);

        return buildAuthResult(user.getId(), request.rememberMe(), request.clientType());
    }

    /**
     * Authenticates the user with email + password.
     * <p>
     * Returns {@link LoginResult.SessionGranted} when the user has no 2FA method enabled,
     * or {@link LoginResult.TwoFactorRequired} when at least one 2FA method is active
     * (TOTP, Email OTP, or passkey). Admin accounts ({@link UserRole#isAdmin()}) with
     * no 2FA configured still receive a session, but {@code mustSetup2FA=true} signals
     * the client to redirect the user to the 2FA setup page immediately.
     * <p>
     * Performs user lookup before lockout check to prevent email enumeration attacks.
     * Lockout is scoped to the (clientIp, email) pair so an attacker cannot lock out the
     * legitimate owner from a different IP.
     *
     * @param request  login credentials
     * @param clientIp resolved client IP from {@link RateLimitService#getClientIp}
     * @return {@link LoginResult} — either a full session or a pending 2FA token
     */
    public LoginResult login(LoginRequest request, String clientIp) {
        User user = userRepository.findByEmail(request.email()).orElse(null);

        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Nieprawidłowe dane logowania");
        }

        loginAttemptService.checkLockout(clientIp, request.email());

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginAttemptService.recordFailedAttempt(clientIp, request.email());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Nieprawidłowe dane logowania");
        }

        if (!user.isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Adres e-mail musi być zweryfikowany przed zalogowaniem");
        }

        loginAttemptService.clearAttempts(clientIp, request.email());

        List<String> availableMethods = buildAvailableMethods(user);

        if (!availableMethods.isEmpty()) {
            // Co najmniej jedna metoda 2FA jest włączona — wymagamy drugiego kroku.
            String pendingToken = twoFactorPendingService.createPendingToken(user.getId(), availableMethods);
            return new LoginResult.TwoFactorRequired(pendingToken, availableMethods);
        }

        // Brak 2FA. Admini dostają sesję, ale z flagą mustSetup2FA=true, żeby
        // frontend mógł przekierować ich na stronę konfiguracji 2FA.
        boolean isAdmin = UserRole.valueOf(user.getRole()).isAdmin();
        AuthResult result = buildAuthResult(user.getId(), request.rememberMe(), request.clientType());
        return new LoginResult.SessionGranted(result.sessionToken(), result.rememberMeToken(),
                user.isMustChangePassword(), isAdmin);
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Użytkownik nie znaleziony"));
    }

    /**
     * Zmienia hasło użytkownika.
     * Gdy mustChangePassword=true — currentPassword nie jest wymagane (user właśnie się zalogował).
     * Gdy mustChangePassword=false — currentPassword jest wymagane do weryfikacji.
     */
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Użytkownik nie znaleziony"));

        if (!user.isMustChangePassword()) {
            if (currentPassword == null || currentPassword.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aktualne hasło jest wymagane");
            }
            if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Nieprawidłowe aktualne hasło");
            }
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);
    }

    public void logout(String sessionToken, String rememberMeToken) {
        tokenService.invalidateSession(sessionToken);
        if (rememberMeToken != null) {
            tokenService.invalidateRememberMeToken(rememberMeToken);
        }
    }

    private List<String> buildAvailableMethods(User user) {
        List<String> methods = new ArrayList<>();
        if (user.isTotpEnabled()) methods.add("TOTP");
        if (user.isEmailOtpEnabled()) methods.add("EMAIL_OTP");
        if (!userCredentialRepository.findAllByUserId(user.getId()).isEmpty()) methods.add("PASSKEY");

        // Domyślna metoda trafia na pierwszą pozycję.
        String def = user.getTwoFactorDefaultMethod();
        if (def != null && methods.remove(def)) {
            methods.add(0, def);
        }
        return methods;
    }

    /**
     * Ustawia preferowaną metodę 2FA. Metoda musi być aktualnie włączona na koncie.
     * Gdy podana metoda nie jest dostępna — rzuca 400.
     */
    public void setTwoFactorDefaultMethod(Long userId, String method) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Użytkownik nie znaleziony"));

        boolean available = switch (method) {
            case "TOTP" -> user.isTotpEnabled();
            case "EMAIL_OTP" -> user.isEmailOtpEnabled();
            case "PASSKEY" -> !userCredentialRepository.findAllByUserId(userId).isEmpty();
            default -> false;
        };
        if (!available) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Metoda 2FA '" + method + "' nie jest włączona dla tego konta");
        }

        user.setTwoFactorDefaultMethod(method);
        userRepository.save(user);
    }

    private AuthResult buildAuthResult(Long userId, boolean rememberMe, ClientType clientType) {
        String sessionToken = tokenService.createSession(userId);
        String rememberMeToken = rememberMe
                ? tokenService.createRememberMeToken(userId, clientType)
                : null;
        return new AuthResult(sessionToken, rememberMeToken);
    }
}
