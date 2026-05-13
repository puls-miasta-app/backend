package com.github.PulsMiastaApp.PulsMiasta.Model.Enums;

import java.util.Locale;

/** Cykl życia zgłoszenia obsługiwany po stronie backend/admin. */
public enum PulseStatus {
    NEW("Nowe"),
    PENDING_REVIEW("Oczekuje na weryfikację"),
    CONFIRMED("Potwierdzone"),
    IN_PROGRESS("W realizacji"),
    RESOLVED("Rozwiązane"),
    REJECTED("Odrzucone");

    private final String label;

    PulseStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static PulseStatus fromApi(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String normalized = raw.trim();
        for (PulseStatus s : values()) {
            if (s.label.equalsIgnoreCase(normalized) || s.name().equalsIgnoreCase(normalized)) {
                return s;
            }
        }
        try {
            return PulseStatus.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
