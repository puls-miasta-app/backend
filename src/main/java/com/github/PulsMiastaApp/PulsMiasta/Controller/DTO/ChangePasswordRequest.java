package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

public record ChangePasswordRequest(
        String currentPassword,
        String newPassword
) {}
