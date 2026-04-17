package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

/**
 * Body dla {@code POST /v1/pulses} wysyłanego przez aplikację mobilną
 * (patrz {@code createPulseApiSchema.POST.input} w mobile).
 *
 * <p>Pola opcjonalne — tylko {@code category} i {@code photoAttached} są wymagane.
 * {@code latitude}/{@code longitude}/{@code address}/{@code district}/{@code street}
 * są dodatkowe i służą do wypełnienia pól serwerowych (dedup + feed filtering). Jeżeli
 * mobile ich nie dostarczy, backend zostawi te pola puste.
 */
public record CreatePulseRequest(
        String description,
        String category,
        Boolean photoAttached,
        Double latitude,
        Double longitude,
        String address,
        String district,
        String street
) {}
