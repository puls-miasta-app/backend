package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;

public record UserAdminView(
        Long id,
        String email,
        String firstName,
        String lastName,
        String role,
        boolean emailVerified,
        boolean blocked,
        String blockedAt,
        String blockReason,
        Long blockedByAdminId
) {
    public static UserAdminView from(User user) {
        return new UserAdminView(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole(),
                user.isEmailVerified(),
                user.isBlocked(),
                user.getBlockedAt() != null ? user.getBlockedAt().toString() : null,
                user.getBlockReason(),
                user.getBlockedByAdminId()
        );
    }
}
