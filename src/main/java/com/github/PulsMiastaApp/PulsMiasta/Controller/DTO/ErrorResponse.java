package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ErrorResponse")
public record ErrorResponse(

        @Schema(example = "false")
        boolean success,

        @Schema(example = "Invalid credentials")
        String message,

        @Schema(example = "unauthorized")
        String code
) {
    public static ErrorResponse of(String message) {
        return new ErrorResponse(false, message, null);
    }

    public static ErrorResponse of(String message, String code) {
        return new ErrorResponse(false, message, code);
    }
}
