package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ChatDtos {

    // ─── Requests ─────────────────────────────────────────────────────────────

    public record OpenThreadRequest(
            @NotBlank @Size(max = 300) String subject,
            @NotBlank @Size(max = 4000) String firstMessage
    ) {}

    public record SendMessageRequest(
            @NotBlank @Size(max = 4000) String body
    ) {}

    public record UpdateThreadStatusRequest(
            @NotBlank String status
    ) {}

    public record AssignThreadRequest(
            Long assignedToId
    ) {}

    // ─── Responses ────────────────────────────────────────────────────────────

    public record ChatThreadResponse(
            String id,
            String pulseId,
            String pulseTitle,
            String userId,
            String userFirstName,
            String userLastName,
            String userEmail,
            String assignedToId,
            String assignedToFirstName,
            String assignedToLastName,
            String status,
            String statusLabel,
            String subject,
            int messagesCount,
            String lastMessageAt,
            String createdAt,
            String updatedAt
    ) {}

    public record ChatMessageResponse(
            String id,
            String threadId,
            String senderId,
            String senderFirstName,
            String senderLastName,
            String senderEmail,
            String senderRole,
            String body,
            String createdAt,
            String editedAt
    ) {}

    public record ChatThreadWithMessagesResponse(
            ChatThreadResponse thread,
            java.util.List<ChatMessageResponse> messages,
            int page,
            int totalPages,
            long totalMessages
    ) {}
}
