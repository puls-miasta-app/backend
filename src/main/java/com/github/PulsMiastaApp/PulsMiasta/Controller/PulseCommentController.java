package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.CommentResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.CreateCommentRequest;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ReportCommentRequest;
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
@RequiredArgsConstructor
public class PulseCommentController {

    private final PulseCommentService commentService;

    // ─── Komentarze na pulsie ──────────────────────────────────────────────────

    /** Zwraca komentarze najwyższego poziomu dla pulsu (bez odpowiedzi). */
    @GetMapping("/v1/pulses/{id}/comments")
    public ResponseEntity<SuccessResponse<Map<String, List<CommentResponse>>>> list(
            @PathVariable("id") Long pulseId,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuth(principal);
        List<CommentResponse> comments = commentService.listTopLevel(pulseId, principal.id());
        return ResponseEntity.ok(SuccessResponse.of(Map.of("comments", comments)));
    }

    /** Tworzy nowy komentarz lub odpowiedź (parentCommentId w body). */
    @PostMapping(value = "/v1/pulses/{id}/comments", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<Map<String, CommentResponse>>> create(
            @PathVariable("id") Long pulseId,
            @RequestBody CreateCommentRequest body,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuth(principal);
        requireEmailVerified(principal);
        CommentResponse created = commentService.create(
                pulseId,
                principal.id(),
                body == null ? null : body.body(),
                body == null ? null : body.parentCommentId()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of(Map.of("comment", created)));
    }

    // ─── Odpowiedzi na komentarz ──────────────────────────────────────────────

    /** Zwraca odpowiedzi na wybrany komentarz. */
    @GetMapping("/v1/pulses/{id}/comments/{commentId}/replies")
    public ResponseEntity<SuccessResponse<Map<String, List<CommentResponse>>>> listReplies(
            @PathVariable("id") Long pulseId,
            @PathVariable("commentId") Long commentId,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuth(principal);
        List<CommentResponse> replies = commentService.listReplies(commentId, principal.id());
        return ResponseEntity.ok(SuccessResponse.of(Map.of("replies", replies)));
    }

    // ─── Edycja i usuwanie ────────────────────────────────────────────────────

    @PatchMapping(value = "/v1/pulses/{id}/comments/{commentId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<Map<String, CommentResponse>>> edit(
            @PathVariable("id") Long pulseId,
            @PathVariable("commentId") Long commentId,
            @RequestBody CreateCommentRequest body,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuth(principal);
        CommentResponse updated = commentService.edit(commentId, principal.id(),
                body == null ? null : body.body());
        return ResponseEntity.ok(SuccessResponse.of(Map.of("comment", updated)));
    }

    @DeleteMapping("/v1/pulses/{id}/comments/{commentId}")
    public ResponseEntity<SuccessResponse<Void>> delete(
            @PathVariable("id") Long pulseId,
            @PathVariable("commentId") Long commentId,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuth(principal);
        commentService.deleteOwn(commentId, principal.id());
        return ResponseEntity.ok(SuccessResponse.of(null));
    }

    // ─── Lajki ────────────────────────────────────────────────────────────────

    /** Toggle lajka — jeden request polubi lub odpolubi komentarz. */
    @PostMapping("/v1/comments/{commentId}/like")
    public ResponseEntity<SuccessResponse<Map<String, CommentResponse>>> toggleLike(
            @PathVariable("commentId") Long commentId,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuth(principal);
        CommentResponse response = commentService.toggleLike(commentId, principal.id());
        return ResponseEntity.ok(SuccessResponse.of(Map.of("comment", response)));
    }

    // ─── Zgłoszenia ────────────────────────────────────────────────────────────

    @PostMapping(value = "/v1/comments/{commentId}/report", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<Void>> report(
            @PathVariable("commentId") Long commentId,
            @RequestBody ReportCommentRequest body,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAuth(principal);
        requireEmailVerified(principal);
        commentService.report(
                commentId,
                principal.id(),
                body == null ? null : body.reason(),
                body == null ? null : body.description()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(SuccessResponse.of(null));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static void requireAuth(AuthPrincipal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    private static void requireEmailVerified(AuthPrincipal principal) {
        if (!principal.emailVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Email must be verified");
        }
    }
}
