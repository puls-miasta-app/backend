package com.github.PulsMiastaApp.PulsMiasta.Model.Enums;

/**
 * Priorytet zgłoszenia. Aplikacja mobilna oczekuje jedynie dwóch poziomów:
 * "Pilne" i "Standard" (patrz {@code PulseResponse.priority} w mobile).
 */
public enum PulsePriority {
    PILNE("Pilne"),
    STANDARD("Standard");

    private final String label;

    PulsePriority(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static PulsePriority fromLabel(String label) {
        if (label == null) {
            return null;
        }
        String normalized = label.trim().toLowerCase(java.util.Locale.ROOT);
        for (PulsePriority p : values()) {
            if (p.label.toLowerCase(java.util.Locale.ROOT).equals(normalized)
                    || p.name().toLowerCase(java.util.Locale.ROOT).equals(normalized)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown pulse priority: " + label);
    }
}
