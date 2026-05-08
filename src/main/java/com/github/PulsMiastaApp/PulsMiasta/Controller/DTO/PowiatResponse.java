package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Powiat;

public record PowiatResponse(Long id, String name, Long wojewodztwoId) {
    public static PowiatResponse from(Powiat p) {
        return new PowiatResponse(p.getId(), p.getName(), p.getWojewodztwo().getId());
    }
}
