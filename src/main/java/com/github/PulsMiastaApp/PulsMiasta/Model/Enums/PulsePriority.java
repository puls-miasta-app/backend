package com.github.PulsMiastaApp.PulsMiasta.Model.Enums;

/**
 * Priorytet zgłoszenia. Trzy poziomy: PILNE (zagrożenie bezpieczeństwa), STANDARD
 * (typowa usterka) oraz NISKIE (usterka kosmetyczna, np. plamy, drobne zadrapania).
 */
public enum PulsePriority {
    PILNE("Pilne"),
    STANDARD("Standard"),
    NISKIE("Niskie");

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
