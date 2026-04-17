package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.AreaOptionResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.StreetOptionResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpointy słownikowe dla aplikacji mobilnej:
 * {@code GET /v1/districts} i {@code GET /v1/streets}.
 *
 * <p><b>Uwaga:</b> implementacja używa {@link JdbcTemplate} zamiast Spring Data /
 * Hibernate. Powód: Hibernate 7 + MySQL Connector/J wali SQLState S1009 przy
 * zapytaniach do tabeli {@code pulses}, nawet na prostych SELECT-ach. JdbcTemplate
 * omija Hibernate całkowicie (bez entity mapowania, prepared statement cache,
 * persistence context) — idzie wprost przez DataSource i działa stabilnie.
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class AreaDictionaryController {

    private final JdbcTemplate jdbcTemplate;

    // Separator dla klucza (street, district) w aggregacji. U+001F (Unit Separator)
    // nie wystąpi w żadnej rozsądnej nazwie ulicy/dzielnicy.
    private static final String KEY_SEPARATOR = "\u001F";

    @GetMapping("/districts")
    public ResponseEntity<SuccessResponse<Map<String, List<AreaOptionResponse>>>> listDistricts(
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuthenticated(principal);

        LocalDateTime since = LocalDateTime.now().minusDays(1);

        String sql = "SELECT district, created_at FROM pulses " +
                "WHERE merged_into_pulse_id IS NULL " +
                "AND district IS NOT NULL " +
                "AND district <> ''";

        Map<String, long[]> agg = new HashMap<>(); // [total, today]
        // Uwaga: nie używamy JdbcTemplate.query(...), bo to wewnątrz woła
        // PreparedStatement.executeQuery(), a MySQL Connector/J w tej konfiguracji
        // wali tam "cannot issue statements that do not produce result sets".
        // Obejście: PreparedStatement.execute() + getResultSet() ręcznie.
        jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (ps.execute()) {
                    try (ResultSet rs = ps.getResultSet()) {
                        while (rs.next()) {
                            String district = rs.getString(1);
                            Timestamp createdAt = rs.getTimestamp(2);
                            long[] counters = agg.computeIfAbsent(district, k -> new long[2]);
                            counters[0]++;
                            if (createdAt != null && createdAt.toLocalDateTime().isAfter(since)) {
                                counters[1]++;
                            }
                        }
                    }
                }
            }
            return null;
        });

        List<AreaOptionResponse> items = new ArrayList<>(agg.size());
        agg.forEach((name, counters) ->
                items.add(new AreaOptionResponse(name, counters[0] + " zgłoszeń", (int) counters[1])));
        items.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));

        return ResponseEntity.ok(SuccessResponse.of(Map.of("districts", items)));
    }

    @GetMapping("/streets")
    public ResponseEntity<SuccessResponse<Map<String, List<StreetOptionResponse>>>> listStreets(
            @RequestParam(value = "district", required = false) String district,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuthenticated(principal);

        String districtFilter = (district == null || district.isBlank()) ? null : district;
        LocalDateTime since = LocalDateTime.now().minusDays(1);

        // Klucz = street + 0x1F + district (normalizacja null → "")
        Map<String, long[]> agg = new HashMap<>();

        final String sql;
        final String boundDistrict;
        if (districtFilter == null) {
            sql = "SELECT street, district, created_at FROM pulses " +
                    "WHERE merged_into_pulse_id IS NULL " +
                    "AND street IS NOT NULL " +
                    "AND street <> ''";
            boundDistrict = null;
        } else {
            sql = "SELECT street, district, created_at FROM pulses " +
                    "WHERE merged_into_pulse_id IS NULL " +
                    "AND street IS NOT NULL " +
                    "AND street <> '' " +
                    "AND district = ?";
            boundDistrict = districtFilter;
        }

        // Patrz komentarz w /districts — omijamy executeQuery() bo driver
        // rzuca na nim wyjątek w tej konfiguracji.
        jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (boundDistrict != null) {
                    ps.setString(1, boundDistrict);
                }
                if (ps.execute()) {
                    try (ResultSet rs = ps.getResultSet()) {
                        while (rs.next()) {
                            aggregateStreet(agg, since,
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getTimestamp(3));
                        }
                    }
                }
            }
            return null;
        });

        List<StreetOptionResponse> items = new ArrayList<>(agg.size());
        agg.forEach((key, counters) -> {
            int sep = key.indexOf(KEY_SEPARATOR);
            String streetName = key.substring(0, sep);
            String districtName = key.substring(sep + 1);
            items.add(new StreetOptionResponse(
                    streetName,
                    counters[0] + " zgłoszeń",
                    (int) counters[1],
                    districtName
            ));
        });
        items.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));

        return ResponseEntity.ok(SuccessResponse.of(Map.of("streets", items)));
    }

    private static void aggregateStreet(Map<String, long[]> agg, LocalDateTime since,
                                        String street, String districtName, Timestamp createdAt) {
        String key = street + KEY_SEPARATOR + (districtName == null ? "" : districtName);
        long[] counters = agg.computeIfAbsent(key, k -> new long[2]);
        counters[0]++;
        if (createdAt != null && createdAt.toLocalDateTime().isAfter(since)) {
            counters[1]++;
        }
    }

    private void requireAuthenticated(AuthPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
    }
}
