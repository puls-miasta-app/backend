package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ChatDtos;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Service.ChatService;
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
public class ChatController {

    private final ChatService chatService;

    /** Otwiera nowy wątek czatu dla danego zgłoszenia. */
    @PostMapping(value = "/v1/pulses/{pulseId}/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<Map<String, ChatDtos.ChatThreadResponse>>> openThread(
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

    /** Lista moich wątków czatu (paginacja). */
    @GetMapping("/v1/chat/threads")
    public ResponseEntity<SuccessResponse<Map<String, Object>>> listMyThreads(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
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

    /** Pobiera wątek wraz z wiadomościami (pierwsza strona). */
    @GetMapping("/v1/chat/threads/{threadId}")
    public ResponseEntity<SuccessResponse<Map<String, ChatDtos.ChatThreadWithMessagesResponse>>> getThread(
            @PathVariable Long threadId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuth(principal);
        ChatDtos.ChatThreadWithMessagesResponse result = chatService.getThread(threadId, principal, page, Math.min(size, 100));
        return ResponseEntity.ok(SuccessResponse.of(Map.of("thread", result)));
    }

    /** Lista wiadomości w wątku (paginacja). */
    @GetMapping("/v1/chat/threads/{threadId}/messages")
    public ResponseEntity<SuccessResponse<Map<String, Object>>> getMessages(
            @PathVariable Long threadId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
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

    /** Wysyła wiadomość w wątku. */
    @PostMapping(value = "/v1/chat/threads/{threadId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<Map<String, ChatDtos.ChatMessageResponse>>> sendMessage(
            @PathVariable Long threadId,
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
