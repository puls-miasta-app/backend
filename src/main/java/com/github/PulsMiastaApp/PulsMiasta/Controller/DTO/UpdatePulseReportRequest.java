package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

public record UpdatePulseReportRequest(
        /** Nowy status: "REVIEWED" lub "DISMISSED". */
        String status,
        /** Opcjonalna notatka admina widoczna tylko w panelu. */
        String adminNote,
        /** Jeśli true — odrzuca (REJECTED) puls przy zatwierdzaniu zgłoszenia. */
        boolean rejectPulse
) {}
