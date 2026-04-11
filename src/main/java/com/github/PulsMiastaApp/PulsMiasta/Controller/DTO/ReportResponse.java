package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import java.time.LocalDateTime;
import java.util.List;

public record ReportResponse(
        Long id,
        String status,
        String category,
        String priority,
        String description,
        Double latitude,
        Double longitude,
        String address,
        Integer duplicateCount,
        List<PhotoInfo> photos,
        LocalDateTime createdAt
) {
    public record PhotoInfo(
            Long id,
            String originalFilename,
            String contentType,
            Long fileSize
    ) {}
}
