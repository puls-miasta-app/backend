package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

public final class NotificationDtos {

    private NotificationDtos() {}

    public record RegisterDeviceRequest(
            String pushToken,
            String platform,
            String deviceName,
            String locale
    ) {}

    public record UnregisterDeviceRequest(String pushToken) {}

    public record DeviceResponse(
            Long id,
            String pushToken,
            String platform,
            String deviceName,
            String locale,
            String createdAt,
            String updatedAt
    ) {}

    public record PreferencesRequest(
            Boolean pushEnabled,
            Boolean emailEnabled,
            Boolean nearbyPulsesEnabled,
            Boolean statusUpdatesEnabled,
            Boolean commentRepliesEnabled
    ) {}

    public record PreferencesResponse(
            boolean pushEnabled,
            boolean emailEnabled,
            boolean nearbyPulsesEnabled,
            boolean statusUpdatesEnabled,
            boolean commentRepliesEnabled
    ) {}
}
