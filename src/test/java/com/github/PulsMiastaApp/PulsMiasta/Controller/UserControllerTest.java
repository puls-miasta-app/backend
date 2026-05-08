package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.MeResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Service.UserProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserProfileService userProfileService;

    @InjectMocks
    private UserController userController;

    @Test
    void me_shouldReturnUserPrincipal_whenAuthenticated() {
        AuthPrincipal principal = new AuthPrincipal(
                1L, "jan@example.com", "Jan", "Kowalski", "USER", true,
                Set.of(), Set.of(), Set.of(), Set.of());

        ResponseEntity<SuccessResponse<MeResponse>> result = userController.me(principal);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getData().id()).isEqualTo(1L);
        assertThat(result.getBody().getData().email()).isEqualTo("jan@example.com");
        assertThat(result.getBody().getData().firstName()).isEqualTo("Jan");
        assertThat(result.getBody().getData().lastName()).isEqualTo("Kowalski");
        assertThat(result.getBody().getData().role()).isEqualTo("USER");
    }
}
