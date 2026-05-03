package com.github.PulsMiastaApp.PulsMiasta.Service;

import java.util.Locale;

/**
 * Normalizuje polskie nazwy jednostek administracyjnych do postaci porównywalnej.
 *
 * <p>Problemy które rozwiązuje:
 * <ul>
 *   <li>Nominatim zwraca "Województwo Warmińsko-Mazurskie" → my przechowujemy "warmińsko-mazurskie"</li>
 *   <li>Admin przy tworzeniu konta wpisuje "Warmińsko-Mazurskie" → normalizujemy do "warmińsko-mazurskie"</li>
 *   <li>Różne wielkości liter w porównaniach</li>
 * </ul>
 */
public final class GeoNormalizer {

    private GeoNormalizer() {}

    public static String normalizeWojewodztwo(String raw) {
        return stripPrefix(raw, "województwo ");
    }

    public static String normalizePowiat(String raw) {
        return stripPrefix(raw, "powiat ");
    }

    public static String normalizeGmina(String raw) {
        return stripPrefix(raw, "gmina ");
    }

    /**
     * Lowercase + usunięcie podanego prefiksu jeśli istnieje.
     * Zwraca null dla blank/null inputu.
     */
    private static String stripPrefix(String raw, String prefix) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        String p = prefix.toLowerCase(Locale.ROOT);
        if (s.startsWith(p)) {
            s = s.substring(p.length()).trim();
        }
        return s.isBlank() ? null : s;
    }
}
