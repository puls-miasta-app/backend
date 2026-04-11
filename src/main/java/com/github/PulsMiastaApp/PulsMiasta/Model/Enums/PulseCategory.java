package com.github.PulsMiastaApp.PulsMiasta.Model.Enums;

/**
 * Kategorie zgłoszeń miejskich (pulses) widoczne w aplikacji mobilnej.
 * Wartości labelowe muszą odpowiadać tym oczekiwanym przez frontend
 * (pola w {@code PulseResponse.category} — patrz mobile: "Ruch", "Bezpieczeństwo", "Zieleń").
 */
public enum PulseCategory {
    RUCH("Ruch"),
    BEZPIECZENSTWO("Bezpieczeństwo"),
    ZIELEN("Zieleń");

    private final String label;

    PulseCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Parse the polish label used in API payloads back into the enum. Case-insensitive, diacritic-tolerant. */
    public static PulseCategory fromLabel(String label) {
        if (label == null) {
            return null;
        }
        String normalized = label.trim().toLowerCase(java.util.Locale.ROOT);
        for (PulseCategory c : values()) {
            if (c.label.toLowerCase(java.util.Locale.ROOT).equals(normalized)
                    || c.name().toLowerCase(java.util.Locale.ROOT).equals(normalized)) {
                return c;
            }
        }
        throw new IllegalArgumentException("Unknown pulse category: " + label);
    }
}
