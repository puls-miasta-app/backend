package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.*;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

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
                toGeoList(user.getManagedWojewodztwa(), Wojewodztwo::getId, Wojewodztwo::getName),
                toGeoList(user.getManagedPowiaty(),     Powiat::getId,      Powiat::getName),
                toGeoList(user.getManagedGminy(),       Gmina::getId,       Gmina::getName),
                toGeoList(user.getManagedMiasta(),      Miejscowosc::getId, Miejscowosc::getName)
        );
    }

    private static <T> List<GeoItemResponse> toGeoList(Set<T> items,
            Function<T, Long> idFn, Function<T, String> nameFn) {
        return items.stream()
                .map(item -> new GeoItemResponse(idFn.apply(item), nameFn.apply(item)))
                .sorted(Comparator.comparing(GeoItemResponse::name))
                .toList();
    }
}
