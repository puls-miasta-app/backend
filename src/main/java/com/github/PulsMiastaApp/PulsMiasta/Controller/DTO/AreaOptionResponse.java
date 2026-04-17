package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

/**
 * Option dla dzielnicy. {@code today} = liczba pulses utworzonych w ostatnich 24h.
 * Mobile oczekuje {@code label}, {@code detail}, {@code today}
 * (patrz {@code areaOptionSchema} w mobile).
 */
public record AreaOptionResponse(
        String label,
        String detail,
        int today
) {}
