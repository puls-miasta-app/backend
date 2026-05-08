package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ClientType;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.LoginRequest;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.LoginResult;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.RegisterRequest;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.*;
import com.github.PulsMiastaApp.PulsMiasta.Security.WebAuthn.Service.WebAuthnService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private SudoModeService sudoModeService;

    @MockitoBean
    private WebAuthnService webAuthnService;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @MockitoBean
    private TwoFactorPendingService twoFactorPendingService;

    @MockitoBean
    private TotpService totpService;

    @MockitoBean
    private SudoOtpService sudoOtpService;

    @MockitoBean
    private LoginOtpService loginOtpService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    void register_shouldReturn201AndSetCookies() throws Exception {
        RegisterRequest request = new RegisterRequest("password123", "Jan", "Kowalski", "jan@example.com", false, ClientType.WEB);
        AuthResult authResult = new AuthResult("session-uuid", null);

        when(authService.register(request)).thenReturn(authResult);

        mockMvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("Registered successfully"));
    }

    @Test
    void register_withRememberMe_shouldSetBothCookies() throws Exception {
        RegisterRequest request = new RegisterRequest("password123", "Jan", "Kowalski", "jan@example.com", true, ClientType.MOBILE);
        AuthResult authResult = new AuthResult("session-uuid", "remember-uuid");

        when(authService.register(request)).thenReturn(authResult);

        mockMvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void login_shouldReturn200AndSetCookies() throws Exception {
        LoginRequest request = new LoginRequest("jan@example.com", "password123", false, ClientType.WEB);

        when(authService.login(any(LoginRequest.class), any()))
                .thenReturn(new LoginResult.SessionGranted("session-uuid", null, false, false));

        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void logout_shouldReturn200AndClearCookies() throws Exception {
        mockMvc.perform(post("/v1/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("Logged out successfully"));

        verify(authService).logout(null, null);
    }

    @Test
    void logout_shouldInvalidateBothTokensWhenPresent() throws Exception {
        mockMvc.perform(post("/v1/auth/logout")
                        .cookie(new Cookie("auth_token", "session-123"))
                        .cookie(new Cookie("remember_me", "remember-456")))
                .andExpect(status().isOk());

        verify(authService).logout("session-123", "remember-456");
    }
}
