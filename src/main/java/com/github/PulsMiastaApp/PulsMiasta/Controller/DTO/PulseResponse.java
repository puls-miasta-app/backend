package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import java.util.List;

/**
 * DTO zwracany do aplikacji mobilnej (patrz {@code pulseItemSchema} w
 * {@code frontend_mobile/src/lib/api/feed.schemas.ts}). Pola muszą odpowiadać
 * dokładnie temu kontraktowi — zmiana psuje deserializację Zod po stronie mobile.
 */
public record PulseResponse(
        String id,
        String title,
        String description,
        String category,
        String district,
        String street,
        String time,
        int comments,
        int score,
        String heat,
        String aiNote,
        String imageHint,
        String priority,
        List<PhotoInfo> photos
) {
    public record PhotoInfo(
            Long id,
            String originalFilename,
            String contentType,
            Long fileSize
    ) {}
}
