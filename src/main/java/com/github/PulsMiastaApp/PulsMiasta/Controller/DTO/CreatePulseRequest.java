package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

/**
 * Body dla {@code POST /v1/pulses} wysyłanego przez aplikację mobilną.
 */
public record CreatePulseRequest(
        String description,
        String category,
        Boolean photoAttached,
        Double latitude,
        Double longitude,
        String address,
        String district,
        String street,
        String city
) {}
