package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ChatDtos;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
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

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "Chat — mieszkaniec", description = "Wątki czatu między mieszkańcem a urzędnikami powiązane ze zgłoszeniami. Wymaga zalogowania i zweryfikowanego emaila.")
@SecurityRequirement(name = "cookieAuth")
public class ChatController {

    private final ChatService chatService;

    @Operation(
            summary = "Otwórz wątek czatu dla zgłoszenia",
            description = """
                    Tworzy nowy wątek czatu powiązany ze zgłoszeniem (pulse).
                    Każdy mieszkaniec może mieć tylko jeden wątek dla danego zgłoszenia (unikalność pulse_id + user_id).
                    Wymaga zweryfikowanego adresu email.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Wątek utworzony",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": true,
                                      "data": {
                                        "thread": {
                                          "id": "7",
                                          "pulseId": "123",
                                          "pulseTitle": "Dziura w jezdni ul. Kwiatowa",
                                          "userId": "5",
                                          "userFirstName": "Jan",
                                          "userLastName": "Kowalski",
                                          "userEmail": "jan@example.com",
                                          "assignedToId": null,
                                          "assignedToFirstName": null,
                                          "assignedToLastName": null,
                                          "status": "OPEN",
                                          "statusLabel": "Otwarte",
                                          "subject": "Pytanie o status naprawy",
                                          "messagesCount": 1,
                                          "lastMessageAt": "2025-05-10T10:00:00Z",
                                          "createdAt": "2025-05-10T10:00:00Z",
                                          "updatedAt": "2025-05-10T10:00:00Z"
                                        }
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji (brak subject lub firstMessage, przekroczenie długości)"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Email niezweryfikowany"),
            @ApiResponse(responseCode = "404", description = "Zgłoszenie (pulse) nie istnieje"),
            @ApiResponse(responseCode = "409", description = "Wątek dla tego zgłoszenia już istnieje")
    })
    @PostMapping(value = "/v1/pulses/{pulseId}/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<Map<String, ChatDtos.ChatThreadResponse>>> openThread(
            @Parameter(description = "ID zgłoszenia (pulse)", example = "123", required = true)
            @PathVariable Long pulseId,
            @Valid @RequestBody ChatDtos.OpenThreadRequest body,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuth(principal);
        requireEmailVerified(principal);
        ChatDtos.ChatThreadResponse thread = chatService.openThread(pulseId, principal.id(), body.subject(), body.firstMessage());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of(Map.of("thread", thread)));
    }

    @Operation(
            summary = "Lista moich wątków czatu",
            description = "Zwraca paginowaną listę wątków czatu zalogowanego mieszkańca, posortowaną malejąco po dacie ostatniej aktualizacji."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista wątków",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": true,
                                      "data": {
                                        "threads": [ { "id": "7", "status": "OPEN", "subject": "Pytanie o naprawę", "messagesCount": 3, "..." : "..." } ],
                                        "page": 0,
                                        "totalPages": 1,
                                        "total": 1
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia")
    })
    @GetMapping("/v1/chat/threads")
    public ResponseEntity<SuccessResponse<Map<String, Object>>> listMyThreads(
            @Parameter(description = "Numer strony (0-based)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Liczba elementów na stronę (max 100)", example = "20") @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuth(principal);
        Page<ChatDtos.ChatThreadResponse> result = chatService.listMyThreads(principal.id(), page, Math.min(size, 100));
        return ResponseEntity.ok(SuccessResponse.of(Map.of(
                "threads", result.getContent(),
                "page", result.getNumber(),
                "totalPages", result.getTotalPages(),
                "total", result.getTotalElements()
        )));
    }

    @Operation(
            summary = "Szczegóły wątku z wiadomościami",
            description = "Zwraca pełne dane wątku wraz z paginowaną listą wiadomości (domyślnie pierwsza strona, 50 wiadomości, posortowanych chronologicznie)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dane wątku z wiadomościami"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak dostępu do tego wątku"),
            @ApiResponse(responseCode = "404", description = "Wątek nie istnieje")
    })
    @GetMapping("/v1/chat/threads/{threadId}")
    public ResponseEntity<SuccessResponse<Map<String, ChatDtos.ChatThreadWithMessagesResponse>>> getThread(
            @Parameter(description = "ID wątku", example = "7", required = true) @PathVariable Long threadId,
            @Parameter(description = "Numer strony wiadomości (0-based)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Liczba wiadomości na stronę (max 100)", example = "50") @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuth(principal);
        ChatDtos.ChatThreadWithMessagesResponse result = chatService.getThread(threadId, principal, page, Math.min(size, 100));
        return ResponseEntity.ok(SuccessResponse.of(Map.of("thread", result)));
    }

    @Operation(
            summary = "Paginowana lista wiadomości w wątku",
            description = "Zwraca wiadomości posortowane chronologicznie (ASC). Użyj do doładowywania starszych wiadomości."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista wiadomości",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": true,
                                      "data": {
                                        "messages": [
                                          {
                                            "id": "55",
                                            "threadId": "7",
                                            "senderId": "5",
                                            "senderFirstName": "Jan",
                                            "senderLastName": "Kowalski",
                                            "senderRole": "ROLE_USER",
                                            "body": "Dzień dobry, kiedy naprawa?",
                                            "createdAt": "2025-05-10T10:00:00Z",
                                            "editedAt": null
                                          }
                                        ],
                                        "page": 0,
                                        "totalPages": 1,
                                        "total": 1
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak dostępu do wątku"),
            @ApiResponse(responseCode = "404", description = "Wątek nie istnieje")
    })
    @GetMapping("/v1/chat/threads/{threadId}/messages")
    public ResponseEntity<SuccessResponse<Map<String, Object>>> getMessages(
            @Parameter(description = "ID wątku", example = "7", required = true) @PathVariable Long threadId,
            @Parameter(description = "Numer strony (0-based)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Liczba wiadomości na stronę (max 100)", example = "50") @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuth(principal);
        Page<ChatDtos.ChatMessageResponse> result = chatService.getMessages(threadId, principal, page, Math.min(size, 100));
        return ResponseEntity.ok(SuccessResponse.of(Map.of(
                "messages", result.getContent(),
                "page", result.getNumber(),
                "totalPages", result.getTotalPages(),
                "total", result.getTotalElements()
        )));
    }

    @Operation(
            summary = "Wyślij wiadomość w wątku",
            description = """
                    Wysyła nową wiadomość w istniejącym wątku.
                    Po zapisaniu wiadomość jest automatycznie rozgłaszana przez WebSocket
                    do wszystkich subskrybentów tematu `/topic/thread.{threadId}`.
                    Wymaga zweryfikowanego adresu email.
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
                                          "id": "56",
                                          "threadId": "7",
                                          "senderId": "5",
                                          "senderFirstName": "Jan",
                                          "senderLastName": "Kowalski",
                                          "senderRole": "ROLE_USER",
                                          "body": "Dziękuję za informację.",
                                          "createdAt": "2025-05-10T14:30:00Z",
                                          "editedAt": null
                                        }
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Pusta wiadomość lub przekroczono 4000 znaków"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Email niezweryfikowany lub brak dostępu do wątku"),
            @ApiResponse(responseCode = "404", description = "Wątek nie istnieje")
    })
    @PostMapping(value = "/v1/chat/threads/{threadId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<Map<String, ChatDtos.ChatMessageResponse>>> sendMessage(
            @Parameter(description = "ID wątku", example = "7", required = true) @PathVariable Long threadId,
            @Valid @RequestBody ChatDtos.SendMessageRequest body,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuth(principal);
        requireEmailVerified(principal);
        ChatDtos.ChatMessageResponse msg = chatService.sendMessage(threadId, principal, body.body());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of(Map.of("message", msg)));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static void requireAuth(AuthPrincipal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    private static void requireEmailVerified(AuthPrincipal principal) {
        if (!principal.emailVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Email must be verified");
        }
    }
}
