package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

public record PhotoUploadResponse(
        Long photoId,
        String objectKey,
        String originalFilename,
        String contentType,
        Long fileSize,
        String message
) {
}
