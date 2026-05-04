package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

public record CommentReportResponse(
        String id,
        String commentId,
        String pulseId,
        /** Aktualna treść komentarza (może być "[Usunięto]" po soft-delete). */
        String commentBody,
        /** Snapshot treści z momentu zgłoszenia — zawsze dostępny dla admina. */
        String originalBody,
        Long reporterId,
        String reporterEmail,
        String reason,
        String description,
        String status,
        String adminNote,
        Long reviewedById,
        String reviewedByEmail,
        String reviewedAt,
        String createdAt
) {}
