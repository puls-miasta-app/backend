package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ClientType;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.LoginRequest;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.LoginResult;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.RegisterRequest;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final TwoFactorPendingService twoFactorPendingService;

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
     *
     * @param request login credentials
     * @return {@link LoginResult} — either a full session or a pending 2FA token
     */
    public LoginResult login(LoginRequest request) {
        User user = findByEmail(request.email());

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        boolean requiresTwoFactor = user.isTotpEnabled() || "ADMIN".equals(user.getRole());
        if (requiresTwoFactor) {
            if (!user.isTotpEnabled()) {
                // ADMIN without TOTP configured — block login until TOTP is set up
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "ADMIN accounts must configure TOTP before logging in. " +
                                "Please contact an administrator or use passkey login.");
            }
            String pendingToken = twoFactorPendingService.createPendingToken(user.getId());
            return new LoginResult.TwoFactorRequired(pendingToken);
        }

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

    private AuthResult buildAuthResult(Long userId, boolean rememberMe, ClientType clientType) {
        String sessionToken = tokenService.createSession(userId);
        String rememberMeToken = rememberMe
                ? tokenService.createRememberMeToken(userId, clientType)
                : null;
        return new AuthResult(sessionToken, rememberMeToken);
    }
}
