package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ClientType;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.LoginRequest;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.RegisterRequest;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TokenService tokenService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    @Test
    void register_shouldCreateUserAndReturnToken() {
        RegisterRequest request = new RegisterRequest("password123", "Jan", "Kowalski", "jan@example.com", false, ClientType.WEB);

        when(userRepository.existsByEmail("jan@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed-password");
        when(tokenService.createSession(any(Long.class))).thenReturn("session-token-uuid");

        AuthResult result = authService.register(request);

        verify(userRepository).save(argThat(user ->
                user.getPasswordHash().equals("hashed-password") &&
                        user.getFirstName().equals("Jan") &&
                        user.getLastName().equals("Kowalski") &&
                        user.getEmail().equals("jan@example.com") &&
                        user.getRole().equals("USER")
        ));
        assertThat(result.sessionToken()).isEqualTo("session-token-uuid");
        assertThat(result.rememberMeToken()).isNull();
    }

    @Test
    void register_shouldThrowWhenEmailExists() {
        RegisterRequest request = new RegisterRequest("password123", "Jan", "Kowalski", "jan@example.com", false, ClientType.WEB);

        when(userRepository.existsByEmail("jan@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessage("Email already in use");
    }

    @Test
    void register_withRememberMe_shouldCreateRememberMeToken() {
        RegisterRequest request = new RegisterRequest("password123", "Jan", "Kowalski", "jan@example.com", true, ClientType.MOBILE);

        when(userRepository.existsByEmail("jan@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed-password");
        when(tokenService.createSession(any(Long.class))).thenReturn("session-token");
        when(tokenService.createRememberMeToken(any(Long.class), eq(ClientType.MOBILE))).thenReturn("remember-token");

        AuthResult result = authService.register(request);

        assertThat(result.rememberMeToken()).isEqualTo("remember-token");
    }

    @Test
    void login_shouldReturnTokenOnValidCredentials() {
        User user = new User();
        user.setId(1L);
        user.setPasswordHash("hashed-password");
        user.setEmail("jan@example.com");
        user.setRole("USER");

        LoginRequest request = new LoginRequest("jan@example.com", "password123", false, ClientType.WEB);

        when(userRepository.findByEmail("jan@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashed-password")).thenReturn(true);
        when(tokenService.createSession(1L)).thenReturn("session-token");

        AuthResult result = authService.login(request);

        assertThat(result.sessionToken()).isEqualTo("session-token");
        assertThat(result.rememberMeToken()).isNull();
    }

    @Test
    void login_withRememberMe_shouldCreateRememberMeToken() {
        User user = new User();
        user.setId(2L);
        user.setPasswordHash("hashed-password");
        user.setEmail("jan@example.com");
        user.setRole("USER");

        LoginRequest request = new LoginRequest("jan@example.com", "password123", true, ClientType.MOBILE);

        when(userRepository.findByEmail("jan@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashed-password")).thenReturn(true);
        when(tokenService.createSession(2L)).thenReturn("session-token");
        when(tokenService.createRememberMeToken(2L, ClientType.MOBILE)).thenReturn("remember-token");

        AuthResult result = authService.login(request);

        assertThat(result.rememberMeToken()).isEqualTo("remember-token");
    }

    @Test
    void login_shouldThrowOnInvalidPassword() {
        User user = new User();
        user.setId(1L);
        user.setPasswordHash("hashed-password");
        user.setEmail("jan@example.com");
        user.setRole("USER");

        LoginRequest request = new LoginRequest("jan@example.com", "wrong-password", false, ClientType.WEB);

        when(userRepository.findByEmail("jan@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "hashed-password")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessage("Invalid credentials");
    }

    @Test
    void login_shouldThrowOnInvalidEmail() {
        LoginRequest request = new LoginRequest("nonexistent@example.com", "password123", false, ClientType.WEB);

        when(userRepository.findByEmail("nonexistent@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessage("Invalid credentials");
    }

    @Test
    void findByEmail_shouldReturnUserOnValidEmail() {
        User user = new User();
        user.setId(1L);
        user.setEmail("jan@example.com");

        when(userRepository.findByEmail("jan@example.com")).thenReturn(Optional.of(user));

        User result = authService.findByEmail("jan@example.com");

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
    }

    @Test
    void findByEmail_shouldThrowOnInvalidEmail() {
        when(userRepository.findByEmail("nonexistent@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.findByEmail("nonexistent@example.com"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessage("Invalid credentials");
    }

    @Test
    void logout_shouldInvalidateBothTokens() {
        String sessionToken = "session-123";
        String rememberMeToken = "remember-456";

        authService.logout(sessionToken, rememberMeToken);

        verify(tokenService).invalidateSession("session-123");
        verify(tokenService).invalidateRememberMeToken("remember-456");
    }

    @Test
    void logout_shouldHandleNullRememberMeToken() {
        String sessionToken = "session-123";

        authService.logout(sessionToken, null);

        verify(tokenService).invalidateSession("session-123");
        verify(tokenService, never()).invalidateRememberMeToken(any());
    }
}
