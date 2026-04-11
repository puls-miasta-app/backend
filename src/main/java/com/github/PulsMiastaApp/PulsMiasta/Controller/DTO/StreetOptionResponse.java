package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

/**
 * Option dla ulicy (rozszerza AreaOption o pole {@code district}).
 * Patrz {@code streetOptionSchema} w mobile.
 */
public record StreetOptionResponse(
        String label,
        String detail,
        int today,
        String district
) {}
