package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

public record PulseReportResponse(
        String id,
        String pulseId,
        String pulseTitle,
        String pulseCity,
        String pulseDistrict,
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
