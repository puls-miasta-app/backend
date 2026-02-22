package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Getter
public class SuccessResponse<T> {

    @Schema(example = "true")
    private final boolean success;
    @Schema(example = "{\"id\": 1, \"username\": \"john_doe\"}")
    private final T data;

    public SuccessResponse(boolean success, T data) {
        this.success = success;
        this.data = data;
    }

    public static <T> SuccessResponse<T> of(T data) {
        return new SuccessResponse<>(true, data);
    }

}
