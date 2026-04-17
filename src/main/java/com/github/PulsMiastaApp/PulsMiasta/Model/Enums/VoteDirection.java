package com.github.PulsMiastaApp.PulsMiasta.Model.Enums;

/** Kierunek głosu użytkownika na pulse (up/down). */
public enum VoteDirection {
    UP,
    DOWN;

    public static VoteDirection fromApi(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "up" -> UP;
            case "down" -> DOWN;
            default -> throw new IllegalArgumentException("Unknown vote direction: " + raw);
        };
    }

    public String toApi() {
        return this == UP ? "up" : "down";
    }
}
