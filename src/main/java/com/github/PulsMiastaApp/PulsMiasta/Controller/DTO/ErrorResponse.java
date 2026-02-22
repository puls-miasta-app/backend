package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ErrorResponse")
public record ErrorResponse(

        @Schema(example = "false")
        boolean success,

        @Schema(example = "Invalid credentials")
        String message
) {
    public static ErrorResponse of(String message) {
        return new ErrorResponse(false, message);
    }
}
