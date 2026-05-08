package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Gmina;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Miejscowosc;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Powiat;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Wojewodztwo;

public record MiejscowoscSearchResponse(
        Long id,
        String name,
        Long gminaId,
        String gmina,
        Long powiatId,
        String powiat,
        Long wojewodztwoId,
        String wojewodztwo,
        Double lat,
        Double lng
) {
    public static MiejscowoscSearchResponse from(Miejscowosc m) {
        Gmina g = m.getGmina();
        Powiat p = g.getPowiat();
        Wojewodztwo w = p.getWojewodztwo();
        return new MiejscowoscSearchResponse(
                m.getId(), m.getName(),
                g.getId(), g.getName(),
                p.getId(), p.getName(),
                w.getId(), w.getName(),
                m.getLat(), m.getLng()
        );
    }
}
