package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record AdminUserResponse(
        Long id,
        String email,
        String firstName,
        String lastName,
        String role,
        List<GeoItemResponse> managedWojewodztwa,
        List<GeoItemResponse> managedPowiaty,
        List<GeoItemResponse> managedGminy,
        List<GeoItemResponse> managedMiasta
) {
    public record GeoItemResponse(Long id, String name) {}

    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole(),
                toList(user.getManagedWojewodztwa()),
                toListPow(user.getManagedPowiaty()),
                toListGm(user.getManagedGminy()),
                toListMiej(user.getManagedMiasta())
        );
    }

    private static List<GeoItemResponse> toList(Set<Wojewodztwo> items) {
        return items.stream()
                .map(w -> new GeoItemResponse(w.getId(), w.getName()))
                .sorted(java.util.Comparator.comparing(GeoItemResponse::name))
                .collect(Collectors.toList());
    }

    private static List<GeoItemResponse> toListPow(Set<Powiat> items) {
        return items.stream()
                .map(p -> new GeoItemResponse(p.getId(), p.getName()))
                .sorted(java.util.Comparator.comparing(GeoItemResponse::name))
                .collect(Collectors.toList());
    }

    private static List<GeoItemResponse> toListGm(Set<Gmina> items) {
        return items.stream()
                .map(g -> new GeoItemResponse(g.getId(), g.getName()))
                .sorted(java.util.Comparator.comparing(GeoItemResponse::name))
                .collect(Collectors.toList());
    }

    private static List<GeoItemResponse> toListMiej(Set<Miejscowosc> items) {
        return items.stream()
                .map(m -> new GeoItemResponse(m.getId(), m.getName()))
                .sorted(java.util.Comparator.comparing(GeoItemResponse::name))
                .collect(Collectors.toList());
    }
}
