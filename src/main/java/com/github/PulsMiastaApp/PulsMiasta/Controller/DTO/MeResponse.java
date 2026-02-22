package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;

public record MeResponse(
        Long id,
        String email,
        String firstName,
        String lastName,
        String role
) {
    public static MeResponse from(AuthPrincipal principal) {
        return new MeResponse(
                principal.id(),
                principal.email(),
                principal.firstName(),
                principal.lastName(),
                principal.role()
        );
    }
}
