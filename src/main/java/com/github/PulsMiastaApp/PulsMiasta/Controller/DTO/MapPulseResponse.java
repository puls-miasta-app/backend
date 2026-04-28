package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Odpowiedź GeoJSON FeatureCollection — kompatybilna z react-leaflet,
 * react-map-gl (Mapbox), deck.gl, Google Maps React i każdą inną
 * biblioteką mapową wspierającą standard GeoJSON (RFC 7946).
 *
 * <p>Struktura:
 * <pre>
 * {
 *   "type": "FeatureCollection",
 *   "features": [
 *     {
 *       "type": "Feature",
 *       "id": 42,
 *       "geometry": { "type": "Point", "coordinates": [lng, lat] },
 *       "properties": {
 *         "id": "42",
 *         "title": "...",
 *         "category": "Ruch",
 *         "status": "Nowe",
 *         "priority": "Standard",
 *         "score": 5,
 *         "comments": 3,
 *         "district": "Śródmieście",
 *         "street": "Marszałkowska",
 *         "city": "Warszawa",
 *         "heat": "Wysokie",
 *         "createdAt": "2025-04-28T12:00:00",
 *         "photoCount": 1
 *       }
 *     }
 *   ]
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MapPulseResponse(
        String type,
        List<Feature> features
) {

    public static final String TYPE = "FeatureCollection";

    public static MapPulseResponse of(List<Feature> features) {
        return new MapPulseResponse(TYPE, features);
    }

    public record Feature(
            String type,
            Long id,
            Geometry geometry,
            Map<String, Object> properties
    ) {
        public static final String TYPE = "Feature";

        public static Feature of(Long id, double lng, double lat, Map<String, Object> properties) {
            return new Feature(TYPE, id, new Geometry("Point", List.of(lng, lat)), properties);
        }
    }

    public record Geometry(
            String type,
            List<Double> coordinates
    ) {}
}
