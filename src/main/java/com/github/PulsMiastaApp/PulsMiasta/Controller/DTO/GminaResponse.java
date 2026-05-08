package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Gmina;

public record GminaResponse(Long id, String name, String type, Long powiatId) {
    public static GminaResponse from(Gmina g, Long powiatId) {
        return new GminaResponse(g.getId(), g.getName(), g.getType(), powiatId);
    }
}
