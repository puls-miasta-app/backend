package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import jakarta.validation.constraints.*;

public record RegisterRequest(

        @NotBlank
        @Pattern(regexp = "\\d{11}", message = "PESEL must be exactly 11 digits")
        String pesel,

        @NotBlank
        @Size(min = 8, message = "Password must be at least 8 characters")
        String password,

        @NotBlank
        @Size(max = 100)
        String firstName,

        @NotBlank
        @Size(max = 100)
        String lastName,

        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        boolean rememberMe,

        @NotNull
        ClientType clientType
) {
}
