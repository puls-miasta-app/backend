package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

public record ReportPulseRequest(
        /** Powód zgłoszenia: SPAM, DUPLICATE, INAPPROPRIATE_CONTENT, WRONG_LOCATION, MISLEADING, OTHER */
        String reason,
        /** Opcjonalny opis — max 1000 znaków. */
        String description
) {}
