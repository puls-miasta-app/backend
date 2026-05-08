package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Wojewodztwo;

public record WojewodztwoResponse(Long id, String name) {
    public static WojewodztwoResponse from(Wojewodztwo w) {
        return new WojewodztwoResponse(w.getId(), w.getName());
    }
}
