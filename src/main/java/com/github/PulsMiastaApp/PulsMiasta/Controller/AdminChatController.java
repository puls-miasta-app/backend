package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ChatDtos;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "Chat — admin", description = "Zarządzanie wątkami czatu przez urzędników. Widoczność wątków ograniczona do obszaru administracyjnego admina (miasto/gmina/powiat/województwo). Wymaga roli ADMIN lub wyższej.")
@SecurityRequirement(name = "cookieAuth")
public class AdminChatController {

    private final ChatService chatService;

    @Operation(
            summary = "Lista wątków w obszarze admina",
            description = """
                    Zwraca paginowaną listę wątków z obszaru administracyjnego zalogowanego urzędnika.
                    Zakres jest automatycznie ograniczany na podstawie roli:
                    - `ADMIN_MIASTA` → widzi wątki swojego miasta
                    - `ADMIN_GMINY` → widzi wątki swojej gminy
                    - `ADMIN_POWIATU` → widzi wątki swojego powiatu
                    - `ADMIN_WOJEWODZTWA` → widzi wątki swojego województwa
                    - `SUPER_ADMIN` → widzi wszystkie wątki

                    Opcjonalne filtrowanie po statusie przez parametr `status`.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista wątków",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": true,
                                      "data": {
                                        "threads": [ { "id": "7", "status": "OPEN", "subject": "Pytanie o naprawę", "..." : "..." } ],
                                        "page": 0,
                                        "totalPages": 3,
                                        "total": 58
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli administratora")
    })
    @GetMapping("/v1/admin/chat/threads")
    public ResponseEntity<SuccessResponse<Map<String, Object>>> listThreads(
            @Parameter(description = "Filtr statusu: OPEN | IN_PROGRESS | NEEDS_INFO | CLOSED (brak = wszystkie)",
                    example = "OPEN") @RequestParam(required = false) String status,
            @Parameter(description = "Numer strony (0-based)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Liczba elementów na stronę (max 100)", example = "20") @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        Page<ChatDtos.ChatThreadResponse> result = chatService.listForAdmin(principal, status, page, Math.min(size, 100));
        return ResponseEntity.ok(SuccessResponse.of(Map.of(
                "threads", result.getContent(),
                "page", result.getNumber(),
                "totalPages", result.getTotalPages(),
                "total", result.getTotalElements()
        )));
    }

    @Operation(
            summary = "Szczegóły wątku (widok admina)",
            description = "Zwraca pełne dane wątku wraz z paginowanymi wiadomościami. Admin może odczytać każdy wątek ze swojego obszaru, niezależnie od tego, czy jest do niego przypisany."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Wątek z wiadomościami"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli administratora lub wątek poza obszarem admina"),
            @ApiResponse(responseCode = "404", description = "Wątek nie istnieje")
    })
    @GetMapping("/v1/admin/chat/threads/{threadId}")
    public ResponseEntity<SuccessResponse<Map<String, ChatDtos.ChatThreadWithMessagesResponse>>> getThread(
            @Parameter(description = "ID wątku", example = "7", required = true) @PathVariable Long threadId,
            @Parameter(description = "Numer strony wiadomości (0-based)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Liczba wiadomości na stronę (max 100)", example = "50") @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        ChatDtos.ChatThreadWithMessagesResponse result = chatService.getThread(threadId, principal, page, Math.min(size, 100));
        return ResponseEntity.ok(SuccessResponse.of(Map.of("thread", result)));
    }

    @Operation(
            summary = "Paginowana lista wiadomości (widok admina)",
            description = "Wiadomości posortowane chronologicznie (ASC). Użyj do doładowania starszych stron."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista wiadomości"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli administratora lub wątek poza obszarem"),
            @ApiResponse(responseCode = "404", description = "Wątek nie istnieje")
    })
    @GetMapping("/v1/admin/chat/threads/{threadId}/messages")
    public ResponseEntity<SuccessResponse<Map<String, Object>>> getMessages(
            @Parameter(description = "ID wątku", example = "7", required = true) @PathVariable Long threadId,
            @Parameter(description = "Numer strony (0-based)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Liczba wiadomości na stronę (max 100)", example = "50") @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        Page<ChatDtos.ChatMessageResponse> result = chatService.getMessages(threadId, principal, page, Math.min(size, 100));
        return ResponseEntity.ok(SuccessResponse.of(Map.of(
                "messages", result.getContent(),
                "page", result.getNumber(),
                "totalPages", result.getTotalPages(),
                "total", result.getTotalElements()
        )));
    }

    @Operation(
            summary = "Admin odpowiada na wątek",
            description = """
                    Wysyła wiadomość od strony admina. Po zapisaniu wiadomość jest automatycznie rozgłaszana
                    przez WebSocket do `/topic/thread.{threadId}`. Mieszkaniec otrzyma też powiadomienie push.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Wiadomość wysłana",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": true,
                                      "data": {
                                        "message": {
                                          "id": "57",
                                          "threadId": "7",
                                          "senderId": "42",
                                          "senderFirstName": "Anna",
                                          "senderLastName": "Nowak",
                                          "senderRole": "ADMIN_MIASTA",
                                          "body": "Dziękujemy za zgłoszenie. Planujemy naprawę w ciągu 14 dni.",
                                          "createdAt": "2025-05-11T08:15:00Z",
                                          "editedAt": null
                                        }
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Pusta wiadomość lub przekroczono 4000 znaków"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli administratora lub wątek poza obszarem"),
            @ApiResponse(responseCode = "404", description = "Wątek nie istnieje")
    })
    @PostMapping(value = "/v1/admin/chat/threads/{threadId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<Map<String, ChatDtos.ChatMessageResponse>>> sendMessage(
            @Parameter(description = "ID wątku", example = "7", required = true) @PathVariable Long threadId,
            @Valid @RequestBody ChatDtos.SendMessageRequest body,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        ChatDtos.ChatMessageResponse msg = chatService.sendMessage(threadId, principal, body.body());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of(Map.of("message", msg)));
    }

    @Operation(
            summary = "Zmień status wątku",
            description = """
                    Aktualizuje status wątku. Dostępne wartości:
                    - `OPEN` — Otwarte
                    - `IN_PROGRESS` — W toku
                    - `NEEDS_INFO` — Wymaga uzupełnienia (mieszkaniec dostanie powiadomienie push)
                    - `CLOSED` — Zamknięte
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status zmieniony, zwraca zaktualizowany wątek"),
            @ApiResponse(responseCode = "400", description = "Nieprawidłowa wartość statusu"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli administratora lub wątek poza obszarem"),
            @ApiResponse(responseCode = "404", description = "Wątek nie istnieje")
    })
    @PatchMapping(value = "/v1/admin/chat/threads/{threadId}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<Map<String, ChatDtos.ChatThreadResponse>>> updateStatus(
            @Parameter(description = "ID wątku", example = "7", required = true) @PathVariable Long threadId,
            @Valid @RequestBody ChatDtos.UpdateThreadStatusRequest body,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        ChatDtos.ChatThreadResponse thread = chatService.updateStatus(threadId, principal, body.status());
        return ResponseEntity.ok(SuccessResponse.of(Map.of("thread", thread)));
    }

    @Operation(
            summary = "Przypisz/odepnij admina od wątku",
            description = """
                    Przypisuje urzędnika (`assignedToId`) do wątku. Podanie `null` odpisuje obecnego urzędnika.
                    Urzędnik musi należeć do tego samego obszaru administracyjnego co wątek.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Przypisanie zaktualizowane, zwraca wątek"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli administratora lub próba przypisania admina spoza obszaru"),
            @ApiResponse(responseCode = "404", description = "Wątek lub wskazany admin nie istnieje")
    })
    @PatchMapping(value = "/v1/admin/chat/threads/{threadId}/assign", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<Map<String, ChatDtos.ChatThreadResponse>>> assignThread(
            @Parameter(description = "ID wątku", example = "7", required = true) @PathVariable Long threadId,
            @Valid @RequestBody ChatDtos.AssignThreadRequest body,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        ChatDtos.ChatThreadResponse thread = chatService.assignThread(threadId, principal, body.assignedToId());
        return ResponseEntity.ok(SuccessResponse.of(Map.of("thread", thread)));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static void requireAdmin(AuthPrincipal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!principal.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Dostęp tylko dla administratorów");
    }
}
