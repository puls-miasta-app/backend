package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Gmina;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Miejscowosc;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Powiat;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Wojewodztwo;

public record AdminUserResponse(
        Long id,
        String email,
        String firstName,
        String lastName,
        String role,
        String managedWojewodztwo,
        Long managedWojewodztwoId,
        String managedPowiat,
        Long managedPowiatId,
        String managedGmina,
        Long managedGminaId,
        String managedMiasto,
        Long managedMiastoId
) {
    public static AdminUserResponse from(User user) {
        Wojewodztwo woj = user.getManagedWojewodztwoRef();
        Powiat pow = user.getManagedPowiatRef();
        Gmina gm = user.getManagedGminaRef();
        Miejscowosc miej = user.getManagedMiastoRef();
        return new AdminUserResponse(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole(),
                user.getManagedWojewodztwo(),
                woj != null ? woj.getId() : null,
                user.getManagedPowiat(),
                pow != null ? pow.getId() : null,
                user.getManagedGmina(),
                gm != null ? gm.getId() : null,
                user.getManagedMiasto(),
                miej != null ? miej.getId() : null
        );
    }
}
