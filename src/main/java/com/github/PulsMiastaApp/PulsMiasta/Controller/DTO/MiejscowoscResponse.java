package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Miejscowosc;

public record MiejscowoscResponse(Long id, String name, Double lat, Double lng, Long gminaId) {
    public static MiejscowoscResponse from(Miejscowosc m, Long gminaId) {
        return new MiejscowoscResponse(m.getId(), m.getName(), m.getLat(), m.getLng(), gminaId);
    }
}
