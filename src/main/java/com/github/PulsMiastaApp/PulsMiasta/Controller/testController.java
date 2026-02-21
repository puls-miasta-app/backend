package com.github.PulsMiastaApp.PulsMiasta.Controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/test")
public class testController {

    @RequestMapping("/health")
    public ResponseEntity<String> hello() {
        return ResponseEntity.ok("OK");
    }
}
