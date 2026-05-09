package com.github.PulsMiastaApp.PulsMiasta.Model.Enums;

public enum ChatThreadStatus {
    OPEN,
    IN_PROGRESS,
    NEEDS_INFO,
    CLOSED;

    public String label() {
        return switch (this) {
            case OPEN        -> "Otwarte";
            case IN_PROGRESS -> "W toku";
            case NEEDS_INFO  -> "Wymaga uzupełnienia";
            case CLOSED      -> "Zamknięte";
        };
    }
}
