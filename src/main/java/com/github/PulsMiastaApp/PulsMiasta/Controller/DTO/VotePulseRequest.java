package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

/**
 * Body dla {@code POST /v1/pulses/vote}. {@code pulseId} przychodzi jako String z mobile —
 * mobile generuje id jako string w Zod schemacie.
 */
public record VotePulseRequest(
        String pulseId,
        String direction
) {}
