package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@SpringBootTest
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Mock
    private AuthPrincipal principal;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void me_shouldReturnUserPrincipal_whenAuthenticated() throws Exception {
        when(principal.id()).thenReturn(1L);
        when(principal.email()).thenReturn("jan@example.com");
        when(principal.firstName()).thenReturn("Jan");
        when(principal.lastName()).thenReturn("Kowalski");
        when(principal.role()).thenReturn("USER");

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        mockMvc.perform(MockMvcRequestBuilders.get("/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.email").value("jan@example.com"))
                .andExpect(jsonPath("$.data.firstName").value("Jan"))
                .andExpect(jsonPath("$.data.lastName").value("Kowalski"))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    void me_shouldReturn401_whenNotAuthenticated() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}
