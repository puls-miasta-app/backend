package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import jakarta.validation.constraints.NotBlank;

public record UpdateAdminRequest(
        @NotBlank String role,
        /** Preferuj managedWojewodztwoId — string akceptowany dla wstecznej zgodności */
        String managedWojewodztwo,
        String managedPowiat,
        String managedGmina,
        String managedMiasto,
        Long managedWojewodztwoId,
        Long managedPowiatId,
        Long managedGminaId,
        Long managedMiastoId
) {}
