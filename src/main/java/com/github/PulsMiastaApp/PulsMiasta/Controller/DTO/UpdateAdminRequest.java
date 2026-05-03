package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import jakarta.validation.constraints.NotBlank;

public record UpdateAdminRequest(
        @NotBlank String role,
        String managedWojewodztwo,
        String managedPowiat,
        String managedGmina,
        String managedMiasto
) {}
