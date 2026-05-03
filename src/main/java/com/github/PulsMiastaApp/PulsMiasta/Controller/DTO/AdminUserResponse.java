package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;

public record AdminUserResponse(
        Long id,
        String email,
        String firstName,
        String lastName,
        String role,
        String managedWojewodztwo,
        String managedPowiat,
        String managedGmina,
        String managedMiasto
) {
    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole(),
                user.getManagedWojewodztwo(),
                user.getManagedPowiat(),
                user.getManagedGmina(),
                user.getManagedMiasto()
        );
    }
}
