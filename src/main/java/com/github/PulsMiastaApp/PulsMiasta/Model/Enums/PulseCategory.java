package com.github.PulsMiastaApp.PulsMiasta.Model.Enums;

/**
 * Kategorie zgłoszeń miejskich (pulses) widoczne w aplikacji mobilnej.
 * Wartości labelowe muszą odpowiadać tym oczekiwanym przez frontend
 * (pola w {@code PulseResponse.category} — patrz mobile: "Ruch", "Bezpieczeństwo", "Zieleń").
 */
public enum PulseCategory {
    RUCH("Ruch", false),
    BEZPIECZENSTWO("Bezpieczeństwo", false),
    ZIELEN("Zieleń", false),
    INCYDENTY("Incydenty", true);

    private final String label;
    private final boolean adminOnly;

    PulseCategory(String label, boolean adminOnly) {
        this.label = label;
        this.adminOnly = adminOnly;
    }

    public String label() {
        return label;
    }

    public boolean isAdminOnly() {
        return adminOnly;
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
