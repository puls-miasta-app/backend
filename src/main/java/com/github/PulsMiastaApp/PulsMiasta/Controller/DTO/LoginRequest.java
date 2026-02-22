package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record LoginRequest(

        @NotBlank
        @Pattern(regexp = "\\d{11}", message = "PESEL must be exactly 11 digits")
        String pesel,

        @NotBlank
        String password,

        boolean rememberMe,

        @NotNull
        ClientType clientType
) {
}
