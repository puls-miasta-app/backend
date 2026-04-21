package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.CommentResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.CreateCommentRequest;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Service.PulseCommentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/pulses/{id}/comments")
@RequiredArgsConstructor
public class PulseCommentController {

    private final PulseCommentService commentService;

    @GetMapping
    public ResponseEntity<SuccessResponse<Map<String, List<CommentResponse>>>> list(
            @PathVariable("id") Long pulseId,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        List<CommentResponse> comments = commentService.list(pulseId);
        return ResponseEntity.ok(SuccessResponse.of(Map.of("comments", comments)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<Map<String, CommentResponse>>> create(
            @PathVariable("id") Long pulseId,
            @RequestBody CreateCommentRequest body,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!principal.emailVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Email must be verified before commenting");
        }
        CommentResponse created = commentService.create(
                pulseId,
                principal.id(),
                body == null ? null : body.body()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of(Map.of("comment", created)));
    }
}
