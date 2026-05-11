package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ErrorResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.NotificationDtos;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = """
        Zarządzanie powiadomieniami push i preferencjami użytkownika.

        System obsługuje wielokanałowe powiadomienia push (Expo, FCM, APNs) wysyłane asynchronicznie
        po zdarzeniach takich jak zmiana statusu zgłoszenia, nowy komentarz czy wiadomość czatu.
        Każdy użytkownik może zarejestrować wiele urządzeń i niezależnie konfigurować rodzaje
        otrzymywanych powiadomień.

        Wszystkie endpointy wymagają uwierzytelnienia (JWT cookie lub sesja).
        """)
@SecurityRequirement(name = "cookieAuth")
public class NotificationController {

    private final NotificationService notificationService;

    // =========================================================================
    // Device registration
    // =========================================================================

    @Operation(
            summary = "Zarejestruj urządzenie push",
            description = """
                    Rejestruje token push urządzenia w celu odbierania powiadomień.

                    **Zasady:**
                    - Token musi być unikalny globalnie — jeden token może być przypisany tylko do jednego konta.
                      Próba rejestracji tokenu należącego do innego użytkownika zwraca 409.
                    - Ponowna rejestracja tego samego tokenu przez tego samego użytkownika aktualizuje metadane
                      (nazwę urządzenia, locale) bez tworzenia duplikatu.
                    - Token w odpowiedzi jest zawsze zamaskowany (ostatnie 4 znaki) — pełna wartość nie jest
                      nigdy zwracana przez API ze względów bezpieczeństwa.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Urządzenie zarejestrowane pomyślnie",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RegisterDeviceSuccessResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Brakujące lub nieprawidłowe pola (np. brak pushToken lub platform)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Użytkownik niezalogowany",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Token push jest już zarejestrowany na innym koncie",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<Map<String, NotificationDtos.DeviceResponse>>> register(
            @RequestBody NotificationDtos.RegisterDeviceRequest body,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAuth(principal);
        var device = notificationService.register(principal.id(), body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of(Map.of("device", device)));
    }

    // =========================================================================
    // Device unregistration
    // =========================================================================

    @Operation(
            summary = "Wyrejestruj urządzenie push",
            description = """
                    Usuwa rejestrację tokenu push — urządzenie przestanie otrzymywać powiadomienia.

                    **Zasady:**
                    - Użytkownik może wyrejestrować tylko własne urządzenia. Próba wyrejestrowania
                      tokenu należącego do innego użytkownika zwraca 403.
                    - Operacja jest idempotentna: wyrejestrowanie nieistniejącego tokenu zwraca 200.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Urządzenie wyrejestrowane pomyślnie",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UnregisterDeviceSuccessResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Brakujące pole pushToken",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Użytkownik niezalogowany",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Token push należy do innego użytkownika",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @PostMapping(path = "/unregister", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<Map<String, Boolean>>> unregister(
            @RequestBody NotificationDtos.UnregisterDeviceRequest body,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAuth(principal);
        notificationService.unregister(principal.id(), body == null ? null : body.pushToken());
        return ResponseEntity.ok(SuccessResponse.of(Map.of("success", true)));
    }

    // =========================================================================
    // Device listing
    // =========================================================================

    @Operation(
            summary = "Pobierz listę zarejestrowanych urządzeń",
            description = """
                    Zwraca wszystkie urządzenia push zarejestrowane przez zalogowanego użytkownika.

                    Tokeny push w odpowiedzi są zamaskowane — widoczne są tylko ostatnie 4 znaki.
                    Listy można używać do wyświetlenia użytkownikowi, z których urządzeń jest zalogowany,
                    lub do zarządzania rejestracjami (np. wyrejestrowania nieużywanych urządzeń).
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Lista urządzeń (może być pusta)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ListDevicesSuccessResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Użytkownik niezalogowany",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @GetMapping("/devices")
    public ResponseEntity<SuccessResponse<Map<String, List<NotificationDtos.DeviceResponse>>>> listDevices(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAuth(principal);
        return ResponseEntity.ok(SuccessResponse.of(
                Map.of("devices", notificationService.listDevices(principal.id()))));
    }

    // =========================================================================
    // Preferences
    // =========================================================================

    @Operation(
            summary = "Pobierz preferencje powiadomień",
            description = """
                    Zwraca aktualne preferencje powiadomień zalogowanego użytkownika.

                    Użytkownicy, którzy nigdy nie zmieniali preferencji, otrzymują domyślne wartości
                    (wszystkie kategorie włączone).
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Aktualne preferencje powiadomień",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PreferencesSuccessResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Użytkownik niezalogowany",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @GetMapping("/preferences")
    public ResponseEntity<SuccessResponse<NotificationDtos.PreferencesResponse>> getPreferences(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAuth(principal);
        return ResponseEntity.ok(SuccessResponse.of(notificationService.getPreferences(principal.id())));
    }

    @Operation(
            summary = "Zaktualizuj preferencje powiadomień",
            description = """
                    Aktualizuje preferencje powiadomień zalogowanego użytkownika.

                    **Semantyka częściowej aktualizacji (partial update):**
                    Pola z wartością `null` są ignorowane — zmieniane są wyłącznie pola z wartością `true` lub `false`.
                    Pozwala to na aktualizację jednej preferencji bez konieczności przesyłania wszystkich pozostałych.

                    **Priorytety:**
                    - Wyłączenie `pushEnabled` blokuje wszystkie powiadomienia push niezależnie od pozostałych ustawień.
                    - Poszczególne kategorie (`statusUpdatesEnabled`, `commentRepliesEnabled`) działają tylko gdy
                      `pushEnabled` jest `true`.

                    **Kategorie powiadomień:**
                    | Pole | Zdarzenia wyzwalające |
                    |---|---|
                    | `statusUpdatesEnabled` | Zmiana statusu zgłoszenia, scalenie zgłoszeń |
                    | `commentRepliesEnabled` | Nowy komentarz do zgłoszenia, odpowiedź w wątku komentarzy |
                    | `nearbyPulsesEnabled` | Nowe zgłoszenia w pobliżu lokalizacji użytkownika |
                    | `pushEnabled` | Wszystkie kategorie push (przełącznik globalny) |
                    | `emailEnabled` | Kanał email (infrastruktura gotowa, aktualnie nieaktywny) |
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Preferencje zaktualizowane — zwracany jest aktualny stan po aktualizacji",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PreferencesSuccessResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Nieprawidłowe ciało żądania",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Użytkownik niezalogowany",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    @PutMapping(path = "/preferences", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<NotificationDtos.PreferencesResponse>> updatePreferences(
            @RequestBody NotificationDtos.PreferencesRequest body,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireAuth(principal);
        return ResponseEntity.ok(SuccessResponse.of(
                notificationService.updatePreferences(principal.id(), body)));
    }

    // =========================================================================

    private static void requireAuth(AuthPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
    }

    // =========================================================================
    // OpenAPI response schema helpers (anonymous inner classes for Swagger UI)
    // =========================================================================

    @Schema(name = "RegisterDeviceSuccessResponse", description = "Odpowiedź po pomyślnej rejestracji urządzenia")
    private static class RegisterDeviceSuccessResponse extends SuccessResponse<RegisterDeviceData> {
        RegisterDeviceSuccessResponse() { super(true, null); }
    }

    @Schema(name = "RegisterDeviceData")
    private static class RegisterDeviceData {
        @Schema(description = "Zarejestrowane urządzenie")
        public NotificationDtos.DeviceResponse device;
    }

    @Schema(name = "UnregisterDeviceSuccessResponse", description = "Odpowiedź po pomyślnym wyrejestrowaniu urządzenia")
    private static class UnregisterDeviceSuccessResponse extends SuccessResponse<UnregisterDeviceData> {
        UnregisterDeviceSuccessResponse() { super(true, null); }
    }

    @Schema(name = "UnregisterDeviceData")
    private static class UnregisterDeviceData {
        @Schema(description = "Zawsze true po pomyślnym wyrejestrowaniu", example = "true")
        public boolean success;
    }

    @Schema(name = "ListDevicesSuccessResponse", description = "Lista zarejestrowanych urządzeń użytkownika")
    private static class ListDevicesSuccessResponse extends SuccessResponse<ListDevicesData> {
        ListDevicesSuccessResponse() { super(true, null); }
    }

    @Schema(name = "ListDevicesData")
    private static class ListDevicesData {
        @Schema(description = "Lista urządzeń (pusta gdy użytkownik nie ma zarejestrowanych urządzeń)")
        public List<NotificationDtos.DeviceResponse> devices;
    }

    @Schema(name = "PreferencesSuccessResponse", description = "Aktualne preferencje powiadomień użytkownika")
    private static class PreferencesSuccessResponse extends SuccessResponse<NotificationDtos.PreferencesResponse> {
        PreferencesSuccessResponse() { super(true, null); }
    }
}
