package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ChatDtos {

    // ─── Requests ─────────────────────────────────────────────────────────────

    @Schema(description = "Żądanie otwarcia nowego wątku czatu dla zgłoszenia")
    public record OpenThreadRequest(
            @Schema(description = "Tytuł/temat wątku", example = "Pytanie o status naprawy dziury", maxLength = 300)
            @NotBlank @Size(max = 300) String subject,

            @Schema(description = "Treść pierwszej wiadomości", example = "Dzień dobry, chciałem zapytać o postępy w naprawie...", maxLength = 4000)
            @NotBlank @Size(max = 4000) String firstMessage
    ) {}

    @Schema(description = "Żądanie wysłania wiadomości w wątku")
    public record SendMessageRequest(
            @Schema(description = "Treść wiadomości (max 4000 znaków)", example = "Dziękuję za odpowiedź, rozumiem sytuację.", maxLength = 4000)
            @NotBlank @Size(max = 4000) String body
    ) {}

    @Schema(description = "Żądanie zmiany statusu wątku (tylko admin)")
    public record UpdateThreadStatusRequest(
            @Schema(description = "Nowy status wątku", example = "IN_PROGRESS",
                    allowableValues = {"OPEN", "IN_PROGRESS", "NEEDS_INFO", "CLOSED"})
            @NotBlank String status
    ) {}

    @Schema(description = "Żądanie przypisania admina do wątku (null = odepnij)")
    public record AssignThreadRequest(
            @Schema(description = "ID użytkownika-admina do przypisania; null aby odpiąć", example = "42", nullable = true)
            Long assignedToId
    ) {}

    // ─── Responses ────────────────────────────────────────────────────────────

    @Schema(description = "Dane wątku czatu")
    public record ChatThreadResponse(
            @Schema(description = "ID wątku", example = "7") String id,
            @Schema(description = "ID powiązanego zgłoszenia", example = "123") String pulseId,
            @Schema(description = "Tytuł zgłoszenia", example = "Dziura w jezdni ul. Kwiatowa") String pulseTitle,
            @Schema(description = "ID mieszkańca (inicjatora wątku)", example = "5") String userId,
            @Schema(description = "Imię mieszkańca", example = "Jan") String userFirstName,
            @Schema(description = "Nazwisko mieszkańca", example = "Kowalski") String userLastName,
            @Schema(description = "Email mieszkańca", example = "jan.kowalski@example.com") String userEmail,
            @Schema(description = "ID przypisanego admina; null jeśli nieprzypisany", example = "42", nullable = true) String assignedToId,
            @Schema(description = "Imię przypisanego admina", example = "Anna", nullable = true) String assignedToFirstName,
            @Schema(description = "Nazwisko przypisanego admina", example = "Nowak", nullable = true) String assignedToLastName,
            @Schema(description = "Status wątku", example = "OPEN",
                    allowableValues = {"OPEN", "IN_PROGRESS", "NEEDS_INFO", "CLOSED"}) String status,
            @Schema(description = "Czytelna etykieta statusu (polska)", example = "Otwarte") String statusLabel,
            @Schema(description = "Temat wątku", example = "Pytanie o status naprawy") String subject,
            @Schema(description = "Liczba wiadomości w wątku", example = "3") int messagesCount,
            @Schema(description = "Data ostatniej wiadomości (ISO-8601)", example = "2025-05-10T14:30:00Z", nullable = true) String lastMessageAt,
            @Schema(description = "Data utworzenia wątku (ISO-8601)", example = "2025-05-08T09:00:00Z") String createdAt,
            @Schema(description = "Data ostatniej aktualizacji (ISO-8601)", example = "2025-05-10T14:30:00Z") String updatedAt
    ) {}

    @Schema(description = "Pojedyncza wiadomość czatu")
    public record ChatMessageResponse(
            @Schema(description = "ID wiadomości", example = "55") String id,
            @Schema(description = "ID wątku, do którego należy wiadomość", example = "7") String threadId,
            @Schema(description = "ID nadawcy", example = "5") String senderId,
            @Schema(description = "Imię nadawcy", example = "Jan") String senderFirstName,
            @Schema(description = "Nazwisko nadawcy", example = "Kowalski") String senderLastName,
            @Schema(description = "Email nadawcy", example = "jan.kowalski@example.com") String senderEmail,
            @Schema(description = "Rola nadawcy w momencie wysłania", example = "ROLE_USER",
                    allowableValues = {"ROLE_USER", "ROLE_ADMIN_MIASTA", "ROLE_ADMIN_GMINY",
                            "ROLE_ADMIN_POWIATU", "ROLE_ADMIN_WOJEWODZTWA", "ROLE_SUPER_ADMIN"}) String senderRole,
            @Schema(description = "Odszyfrowana treść wiadomości", example = "Dziękuję za odpowiedź.") String body,
            @Schema(description = "Data wysłania (ISO-8601)", example = "2025-05-10T14:30:00Z") String createdAt,
            @Schema(description = "Data edycji (ISO-8601); null jeśli niemodyfikowana", example = "null", nullable = true) String editedAt
    ) {}

    @Schema(description = "Wątek czatu wraz z paginowaną listą wiadomości")
    public record ChatThreadWithMessagesResponse(
            @Schema(description = "Metadane wątku") ChatThreadResponse thread,
            @Schema(description = "Lista wiadomości na bieżącej stronie") java.util.List<ChatMessageResponse> messages,
            @Schema(description = "Numer bieżącej strony (0-based)", example = "0") int page,
            @Schema(description = "Łączna liczba stron", example = "2") int totalPages,
            @Schema(description = "Łączna liczba wiadomości", example = "87") long totalMessages
    ) {}
}
