package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ClientType;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.LoginRequest;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.RegisterRequest;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.AuthResult;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@SpringBootTest
@TestPropertySource(properties = {
        "auth.session.ttl-minutes=15",
        "auth.remember-me.web.ttl-days=30",
        "auth.remember-me.mobile.ttl-days=90"
})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Mock
    private AuthService authService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void register_shouldReturn201AndSetCookies() throws Exception {
        RegisterRequest request = new RegisterRequest("12345678901", "password123", "Jan", "Kowalski", "jan@example.com", false, ClientType.WEB);
        AuthResult authResult = new AuthResult("session-uuid", null);

        when(authService.register(request)).thenReturn(authResult);

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("Registered successfully"));
    }

    @Test
    void register_withRememberMe_shouldSetBothCookies() throws Exception {
        RegisterRequest request = new RegisterRequest("12345678901", "password123", "Jan", "Kowalski", "jan@example.com", true, ClientType.MOBILE);
        AuthResult authResult = new AuthResult("session-uuid", "remember-uuid");

        when(authService.register(request)).thenReturn(authResult);

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void login_shouldReturn200AndSetCookies() throws Exception {
        LoginRequest request = new LoginRequest("12345678901", "password123", false, ClientType.WEB);
        AuthResult authResult = new AuthResult("session-uuid", null);

        when(authService.login(request)).thenReturn(authResult);

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("Logged in successfully"));
    }

    @Test
    void logout_shouldReturn200AndClearCookies() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("Logged out successfully"));

        verify(authService).logout(null, null);
    }

    @Test
    void logout_shouldInvalidateBothTokensWhenPresent() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/auth/logout")
                        .cookie(new jakarta.servlet.http.Cookie("auth_token", "session-123"))
                        .cookie(new jakarta.servlet.http.Cookie("remember_me", "remember-456")))
                .andExpect(status().isOk());

        verify(authService).logout("session-123", "remember-456");
    }
}
