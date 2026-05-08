package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record UpdateAdminRequest(
        @NotBlank String role,
        List<Long> managedWojewodztwoIds,
        List<Long> managedPowiatIds,
        List<Long> managedGminaIds,
        List<Long> managedMiastoIds
) {}
