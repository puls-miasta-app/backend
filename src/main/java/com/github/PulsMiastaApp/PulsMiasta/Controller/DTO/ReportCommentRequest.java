package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

public record ReportCommentRequest(
        /** Powód zgłoszenia — wartość z CommentReportReason (np. "SPAM", "OFFENSIVE"). */
        String reason,
        /** Opcjonalny opis dodatkowy od zgłaszającego (max 500 znaków). */
        String description
) {}
