package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import io.swagger.v3.oas.annotations.media.Schema;

public final class NotificationDtos {

    private NotificationDtos() {}

    @Schema(name = "RegisterDeviceRequest", description = "Rejestracja urządzenia do odbierania powiadomień push")
    public record RegisterDeviceRequest(
            @Schema(
                    description = "Token push wygenerowany przez SDK platformy (Expo, FCM, APNs)",
                    example = "ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]",
                    requiredMode = Schema.RequiredMode.REQUIRED
            )
            String pushToken,

            @Schema(
                    description = "Platforma urządzenia",
                    example = "expo",
                    allowableValues = {"expo", "fcm", "apns", "web"},
                    requiredMode = Schema.RequiredMode.REQUIRED
            )
            String platform,

            @Schema(
                    description = "Przyjazna nazwa urządzenia wyświetlana na liście urządzeń",
                    example = "iPhone 14 Pro"
            )
            String deviceName,

            @Schema(
                    description = "Ustawienia językowe urządzenia w formacie BCP 47",
                    example = "pl_PL"
            )
            String locale
    ) {}

    @Schema(name = "UnregisterDeviceRequest", description = "Wyrejestrowanie urządzenia — token nie będzie już otrzymywał powiadomień")
    public record UnregisterDeviceRequest(
            @Schema(
                    description = "Token push urządzenia do wyrejestrowania",
                    example = "ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]",
                    requiredMode = Schema.RequiredMode.REQUIRED
            )
            String pushToken
    ) {}

    @Schema(name = "DeviceResponse", description = "Zarejestrowane urządzenie push należące do zalogowanego użytkownika")
    public record DeviceResponse(
            @Schema(description = "Unikalny identyfikator rejestracji urządzenia", example = "42")
            Long id,

            @Schema(
                    description = "Token push urządzenia — zamaskowany (ostatnie 4 znaki), nigdy pełny",
                    example = "****xAbC"
            )
            String pushToken,

            @Schema(
                    description = "Platforma urządzenia",
                    example = "expo",
                    allowableValues = {"expo", "fcm", "apns", "web"}
            )
            String platform,

            @Schema(description = "Przyjazna nazwa urządzenia", example = "iPhone 14 Pro")
            String deviceName,

            @Schema(description = "Ustawienia językowe urządzenia", example = "pl_PL")
            String locale,

            @Schema(description = "Data i czas rejestracji (ISO 8601)", example = "2025-03-15T10:30:00Z")
            String createdAt,

            @Schema(description = "Data i czas ostatniej aktualizacji (ISO 8601)", example = "2025-03-20T08:15:00Z")
            String updatedAt
    ) {}

    @Schema(
            name = "PreferencesRequest",
            description = "Aktualizacja preferencji powiadomień. Pola z wartością null są ignorowane — zmieniane są tylko pola o wartości true/false."
    )
    public record PreferencesRequest(
            @Schema(
                    description = "Globalne włączenie/wyłączenie powiadomień push. Wyłączenie blokuje wszystkie kanały push.",
                    example = "true",
                    nullable = true
            )
            Boolean pushEnabled,

            @Schema(
                    description = "Powiadomienia email (infrastruktura gotowa, kanał jeszcze nieaktywny)",
                    example = "false",
                    nullable = true
            )
            Boolean emailEnabled,

            @Schema(
                    description = "Powiadomienia o nowych zgłoszeniach (pulsach) w pobliżu lokalizacji użytkownika",
                    example = "true",
                    nullable = true
            )
            Boolean nearbyPulsesEnabled,

            @Schema(
                    description = "Powiadomienia o zmianie statusu własnych zgłoszeń (np. przyjęte, w trakcie, rozwiązane)",
                    example = "true",
                    nullable = true
            )
            Boolean statusUpdatesEnabled,

            @Schema(
                    description = "Powiadomienia o komentarzach i odpowiedziach w wątkach, w których uczestniczy użytkownik",
                    example = "true",
                    nullable = true
            )
            Boolean commentRepliesEnabled
    ) {}

    @Schema(name = "PreferencesResponse", description = "Aktualne preferencje powiadomień użytkownika. Użytkownicy bez rekordu preferencji mają domyślnie wszystkie opcje włączone.")
    public record PreferencesResponse(
            @Schema(description = "Globalne powiadomienia push włączone", example = "true")
            boolean pushEnabled,

            @Schema(description = "Powiadomienia email włączone", example = "false")
            boolean emailEnabled,

            @Schema(description = "Powiadomienia o pobliskich zgłoszeniach włączone", example = "true")
            boolean nearbyPulsesEnabled,

            @Schema(description = "Powiadomienia o aktualizacjach statusu zgłoszeń włączone", example = "true")
            boolean statusUpdatesEnabled,

            @Schema(description = "Powiadomienia o komentarzach i odpowiedziach włączone", example = "true")
            boolean commentRepliesEnabled
    ) {}
}
