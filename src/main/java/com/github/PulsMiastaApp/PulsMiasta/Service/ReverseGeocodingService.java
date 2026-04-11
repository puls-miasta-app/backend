package com.github.PulsMiastaApp.PulsMiasta.Service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Reverse geocoding oparty o OpenStreetMap Nominatim (darmowe, nie wymaga klucza API).
 *
 * <p>Uwaga: Nominatim ma politykę "max 1 req/s" — ten serwis jest fire-and-forget,
 * wywoływany asynchronicznie po utworzeniu pulse'a. W produkcji należałoby dodać
 * lokalny cache (Redis) na (lat, lng) zaokrąglone do ~50m, ale dla prostoty tego
 * nie robimy — dedup po stronie pulses i tak mergeuje zgłoszenia blisko siebie.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReverseGeocodingService {

    @Value("${geocoding.nominatim.base-url:https://nominatim.openstreetmap.org}")
    private String baseUrl;

    @Value("${geocoding.nominatim.user-agent:PulsMiasta/1.0 (contact: admin@pulsmiasta.app)}")
    private String userAgent;

    @Value("${geocoding.nominatim.timeout-seconds:5}")
    private int timeoutSeconds;

    private RestClient restClient;

    @PostConstruct
    void init() {
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent) // wymagane przez Nominatim
                .defaultHeader("Accept", "application/json")
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Zwraca przypisanie dzielnica/ulica dla danej współrzędnej lub {@link GeocodedAddress#empty()}
     * przy dowolnym błędzie (brak internetu, błąd API, brak adresu). Serwis nigdy nie rzuca
     * wyjątku — używany w fire-and-forget flow.
     */
    public GeocodedAddress reverse(double latitude, double longitude) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/reverse")
                            .queryParam("lat", latitude)
                            .queryParam("lon", longitude)
                            .queryParam("format", "json")
                            .queryParam("zoom", 18)
                            .queryParam("addressdetails", 1)
                            .queryParam("accept-language", "pl")
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response == null) {
                return GeocodedAddress.empty();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> address = (Map<String, Object>) response.get("address");
            if (address == null) {
                return GeocodedAddress.empty();
            }

            String district = pickDistrict(address);
            String street = pickStreet(address);
            String formatted = asString(response.get("display_name"));

            return new GeocodedAddress(district, street, formatted);
        } catch (Exception e) {
            log.warn("Reverse geocoding failed for ({}, {}): {}", latitude, longitude, e.getMessage());
            return GeocodedAddress.empty();
        }
    }

    /**
     * Nominatim zwraca różne pola w zależności od kraju i typu jednostki.
     * Pierwsze niepuste z: city_district, suburb, district, borough, neighbourhood,
     * quarter, city, town, village.
     */
    private String pickDistrict(Map<String, Object> address) {
        String[] keys = {"city_district", "suburb", "district", "borough", "neighbourhood",
                         "quarter", "city", "town", "village"};
        for (String k : keys) {
            String v = asString(address.get(k));
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private String pickStreet(Map<String, Object> address) {
        String[] keys = {"road", "pedestrian", "footway", "cycleway", "path", "residential"};
        for (String k : keys) {
            String v = asString(address.get(k));
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * Wynik reverse geocodingu. Pola mogą być {@code null}, jeśli Nominatim nie zwrócił
     * odpowiedniej klasyfikacji (np. punkt w środku lasu).
     */
    public record GeocodedAddress(String district, String street, String formattedAddress) {
        public static GeocodedAddress empty() {
            return new GeocodedAddress(null, null, null);
        }

        public boolean hasAny() {
            return district != null || street != null || formattedAddress != null;
        }
    }
}
