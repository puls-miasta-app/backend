package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import java.util.List;

/**
 * DTO zwracany do aplikacji mobilnej (patrz {@code pulseItemSchema} w
 * {@code frontend_mobile/src/lib/api/feed.schemas.ts}).
 */
public record PulseResponse(
        String id,
        String title,
        String description,
        String category,
        String district,
        String street,
        String city,
        String address,
        String time,
        int comments,
        int score,
        String heat,
        String aiNote,
        String imageHint,
        String priority,
        String status,
        Double lat,
        Double lng,
        String createdByEmail,
        String userVote,
        List<PhotoInfo> photos
) {
    public record PhotoInfo(
            Long id,
            String originalFilename,
            String contentType,
            Long fileSize
    ) {}
}
