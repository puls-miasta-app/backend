package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

public record CommentResponse(
        String id,
        String pulseId,
        Long userId,
        String userEmail,
        String userFirstName,
        String userLastName,
        String body,
        String createdAt,
        String editedAt,
        String parentCommentId,
        int likesCount,
        int replyCount,
        boolean userLiked,
        boolean deleted
) {}
