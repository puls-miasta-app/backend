package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

public record UpdateCommentReportRequest(
        /** Nowy status: "REVIEWED" lub "DISMISSED". */
        String status,
        /** Opcjonalna notatka admina widoczna tylko w panelu. */
        String adminNote,
        /** Jeśli true — usuwa (soft-delete) komentarz przy zatwierdzaniu zgłoszenia. */
        boolean deleteComment
) {}
