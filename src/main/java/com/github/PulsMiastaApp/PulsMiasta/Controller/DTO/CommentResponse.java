package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

public record CommentResponse(
        String id,
        String pulseId,
        Long userId,
        String userEmail,
        String userFirstName,
        String userLastName,
        String body,
        String createdAt
) {}
