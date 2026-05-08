package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateAdminRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Size(min = 8) String password,
        @NotBlank String role,
        /** IDs województw z tabeli geo_wojewodztwa. Wymagane dla ADMIN_WOJEWODZTWA i niżej. */
        List<Long> managedWojewodztwoIds,
        /** IDs powiatów. Wymagane dla ADMIN_POWIATU i niżej. */
        List<Long> managedPowiatIds,
        /** IDs gmin. Wymagane dla ADMIN_GMINY i niżej. */
        List<Long> managedGminaIds,
        /** IDs miejscowości. Wymagane dla ADMIN_MIASTA. */
        List<Long> managedMiastoIds
) {}
