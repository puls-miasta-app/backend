package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/test")
public class testController {

    @GetMapping("/health")
    public ResponseEntity<SuccessResponse<String>> health() {
        return ResponseEntity.ok(SuccessResponse.of("OK!!!"));
    }
}
