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

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AdminChatController {

    private final ChatService chatService;

    /** Lista wszystkich wątków w obszarze admina. */
    @GetMapping("/v1/admin/chat/threads")
    public ResponseEntity<SuccessResponse<Map<String, Object>>> listThreads(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        Page<ChatDtos.ChatThreadResponse> result = chatService.listForAdmin(principal, status, page, size);
        return ResponseEntity.ok(SuccessResponse.of(Map.of(
                "threads", result.getContent(),
                "page", result.getNumber(),
                "totalPages", result.getTotalPages(),
                "total", result.getTotalElements()
        )));
    }

    /** Szczegóły wątku + wiadomości (widok admina). */
    @GetMapping("/v1/admin/chat/threads/{threadId}")
    public ResponseEntity<SuccessResponse<Map<String, ChatDtos.ChatThreadWithMessagesResponse>>> getThread(
            @PathVariable Long threadId,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        ChatDtos.ChatThreadWithMessagesResponse result = chatService.getThread(threadId, principal);
        return ResponseEntity.ok(SuccessResponse.of(Map.of("thread", result)));
    }

    /** Lista wiadomości z paginacją (widok admina). */
    @GetMapping("/v1/admin/chat/threads/{threadId}/messages")
    public ResponseEntity<SuccessResponse<Map<String, Object>>> getMessages(
            @PathVariable Long threadId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        Page<ChatDtos.ChatMessageResponse> result = chatService.getMessages(threadId, principal, page, size);
        return ResponseEntity.ok(SuccessResponse.of(Map.of(
                "messages", result.getContent(),
                "page", result.getNumber(),
                "totalPages", result.getTotalPages(),
                "total", result.getTotalElements()
        )));
    }

    /** Admin odpowiada na wątek. */
    @PostMapping(value = "/v1/admin/chat/threads/{threadId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<Map<String, ChatDtos.ChatMessageResponse>>> sendMessage(
            @PathVariable Long threadId,
            @Valid @RequestBody ChatDtos.SendMessageRequest body,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        ChatDtos.ChatMessageResponse msg = chatService.sendMessage(threadId, principal, body.body());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of(Map.of("message", msg)));
    }

    /** Zmiana statusu wątku. */
    @PatchMapping(value = "/v1/admin/chat/threads/{threadId}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<Map<String, ChatDtos.ChatThreadResponse>>> updateStatus(
            @PathVariable Long threadId,
            @Valid @RequestBody ChatDtos.UpdateThreadStatusRequest body,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        ChatDtos.ChatThreadResponse thread = chatService.updateStatus(threadId, principal, body.status());
        return ResponseEntity.ok(SuccessResponse.of(Map.of("thread", thread)));
    }

    /** Przypisanie urzędnika do wątku (null = odepnij). */
    @PatchMapping(value = "/v1/admin/chat/threads/{threadId}/assign", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<Map<String, ChatDtos.ChatThreadResponse>>> assignThread(
            @PathVariable Long threadId,
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
