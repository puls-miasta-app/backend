package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

public record CommentReportResponse(
        String id,
        String commentId,
        String pulseId,
        String commentBody,
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
