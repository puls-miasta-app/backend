package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

public record CreateCommentRequest(
        String body,
        /** null = nowy komentarz najwyższego poziomu; non-null = odpowiedź na komentarz */
        Long parentCommentId
) {}
