package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.MapPulseResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.PulseResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Pulse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseCategory;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseStatus;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Service.PulseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Endpointy dla interaktywnej mapy pulsów — zwracają dane w formacie
 * <a href="https://tools.ietf.org/html/rfc7946">GeoJSON (RFC 7946)</a>,
 * kompatybilnym z bibliotekami mapowymi React:
 * <ul>
 *   <li><b>react-leaflet</b> — {@code <GeoJSON data={...} />}</li>
 *   <li><b>react-map-gl</b> (Mapbox) — {@code <Source type="geojson" data={...} />}</li>
 *   <li><b>deck.gl</b> — {@code new GeoJsonLayer({ data }) }</li>
 *   <li><b>Google Maps React</b> — parsowanie FeatureCollection</li>
 * </ul>
 *
 * <h3>Użycie</h3>
 * <pre>
 * GET /v1/map/pulses?swLat=52.1&swLng=20.8&neLat=52.3&neLng=21.1
 * GET /v1/map/pulses?swLat=52.1&swLng=20.8&neLat=52.3&neLng=21.1&category=Ruch&status=Nowe
 * GET /v1/map/pulses/{id}   — szczegóły pojedynczego pulsów (marker click)
 * </pre>
 */
@RestController
@RequestMapping("/v1/map")
@RequiredArgsConstructor
public class MapController {

    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 2000;
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final PulseService pulseService;

    /**
     * Zwraca pulsę w zadanym bounding box jako GeoJSON FeatureCollection.
     *
     * @param swLat    south-west latitude  (wymagany)
     * @param swLng    south-west longitude (wymagany)
     * @param neLat    north-east latitude  (wymagany)
     * @param neLng    north-east longitude (wymagany)
     * @param category opcjonalny filtr kategorii (label, np. "Ruch")
     * @param status   opcjonalny filtr statusu (label, np. "Nowe")
     * @param limit    max wyników (domyślnie 500, max 2000)
     */
    @GetMapping(path = "/pulses", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<MapPulseResponse>> getPulses(
            @RequestParam("swLat") double swLat,
            @RequestParam("swLng") double swLng,
            @RequestParam("neLat") double neLat,
            @RequestParam("neLng") double neLng,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false, defaultValue = "500") Integer limit,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        validateBounds(swLat, swLng, neLat, neLng);

        PulseCategory cat = category != null && !category.isBlank()
                ? PulseCategory.fromLabel(category) : null;
        PulseStatus st = status != null && !status.isBlank()
                ? PulseStatus.fromApi(status) : null;

        int effectiveLimit = sanitizeLimit(limit);
        boolean isAdmin = principal != null && principal.isAdmin();
        Long userId = principal != null ? principal.id() : null;
        List<Pulse> pulses = pulseService.listMapPulses(
                swLat, swLng, neLat, neLng, cat, st, effectiveLimit, isAdmin, userId);

        List<MapPulseResponse.Feature> features = pulses.stream()
                .filter(p -> p.getLatitude() != null && p.getLongitude() != null)
                .map(MapController::toFeature)
                .toList();

        MapPulseResponse body = MapPulseResponse.of(features);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePublic())
                .body(SuccessResponse.of(body));
    }

    /**
     * Szczegóły pojedynczego pulsów — wywoływane po kliknięciu w marker na mapie.
     * Zwraca pełny {@link PulseResponse} (jak GET /v1/pulses/{id}).
     */
    @GetMapping(path = "/pulses/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<PulseResponse>> getPulseDetail(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        if (principal == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "Authentication required");
        }
        Pulse pulse = pulseService.getAny(id);
        var userVote = pulseService.getUserVote(pulse.getId(), principal.id());
        PulseResponse response = PulseMapper.toResponse(pulse, userVote);
        return ResponseEntity.ok(SuccessResponse.of(response));
    }

    // ---------- mapping ----------

    private static MapPulseResponse.Feature toFeature(Pulse p) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", String.valueOf(p.getId()));
        props.put("title", nullToEmpty(p.getTitle()));
        props.put("category", p.getCategory() != null ? p.getCategory().label() : "");
        props.put("status", p.getStatus() != null ? p.getStatus().label() : "Nowe");
        props.put("priority", p.getPriority() != null ? p.getPriority().label() : "Standard");
        props.put("score", p.score());
        props.put("comments", p.getCommentsCount() != null ? p.getCommentsCount() : 0);
        props.put("district", nullToEmpty(p.getDistrict()));
        props.put("street", nullToEmpty(p.getStreet()));
        props.put("city", nullToEmpty(p.getCity()));
        props.put("heat", nullToEmpty(p.getHeat()));
        props.put("createdAt", p.getCreatedAt() != null ? p.getCreatedAt().format(ISO_FMT) : null);
        props.put("photoCount", p.getPhotos() != null ? p.getPhotos().size() : 0);
        return MapPulseResponse.Feature.of(p.getId(), p.getLongitude(), p.getLatitude(), props);
    }

    // ---------- validation ----------

    private static void validateBounds(double swLat, double swLng, double neLat, double neLng) {
        if (swLat < -90 || swLat > 90 || neLat < -90 || neLat > 90) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Latitude must be between -90 and 90");
        }
        if (swLng < -180 || swLng > 180 || neLng < -180 || neLng > 180) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Longitude must be between -180 and 180");
        }
        if (swLat >= neLat) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "swLat must be less than neLat");
        }
    }

    private static int sanitizeLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
