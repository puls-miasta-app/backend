package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

/**
 * Response dla {@code POST /v1/pulses/vote}. {@code userVote} to "up" | "down" | null,
 * gdzie null oznacza, że użytkownik wycofał głos (toggle).
 */
public record VotePulseResponse(
        String pulseId,
        int newScore,
        String userVote
) {}
