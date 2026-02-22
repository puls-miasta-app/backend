package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LoginRequest(

        @NotBlank
        @Email
        String email,

        @NotBlank
        String password,

        boolean rememberMe,

        @NotNull
        ClientType clientType
) {
}
