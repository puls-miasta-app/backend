package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ClientType;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.LoginRequest;
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
    private final BlindIndexService blindIndexService;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;

    public AuthResult register(RegisterRequest request) {
        String blindIndex = blindIndexService.computeIndex(request.pesel());

        if (userRepository.existsByPeselBlindIndex(blindIndex)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User already exists");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
        }

        User user = new User();
        user.setPeselBlindIndex(blindIndex);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setEmail(request.email());
        user.setRole("USER");

        userRepository.save(user);

        return buildAuthResult(user.getId(), request.rememberMe(), request.clientType());
    }

    public AuthResult login(LoginRequest request) {
        User user = findByPesel(request.pesel());

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        return buildAuthResult(user.getId(), request.rememberMe(), request.clientType());
    }

    /**
     * Finds a user by raw PESEL. Computes the blind index internally,
     * then performs a single indexed lookup in the database.
     */
    public User findByPesel(String rawPesel) {
        String blindIndex = blindIndexService.computeIndex(rawPesel);
        return userRepository.findByPeselBlindIndex(blindIndex)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
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
