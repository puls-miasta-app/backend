package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.CommentReportResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.CommentResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.UpdateCommentReportRequest;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Service.PulseCommentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Panel admina — moderacja komentarzy i obsługa zgłoszeń.
 * Dostęp zabezpieczony przez SecurityConfig (/v1/admin/**).
 * Scope geograficzny przekazywany jest do serwisu z AuthPrincipal.
 */
@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class AdminCommentController {

    private static final int MAX_PAGE_SIZE = 100;

    private final PulseCommentService commentService;

    // ─── Komentarze pulsu ─────────────────────────────────────────────────────

    @GetMapping("/pulses/{pulseId}/comments")
    public ResponseEntity<SuccessResponse<AdminCommentListResponse>> listComments(
            @PathVariable("pulseId") Long pulseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        int safeSize = clampSize(size);
        int safePage = Math.max(0, page);
        Page<CommentResponse> result = commentService.listForAdmin(pulseId, safePage, safeSize);
        return ResponseEntity.ok(SuccessResponse.of(new AdminCommentListResponse(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        )));
    }

    /** Moderacyjne usunięcie komentarza — weryfikuje scope admina w serwisie. */
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<SuccessResponse<Void>> deleteComment(
            @PathVariable("commentId") Long commentId,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        commentService.deleteAsAdmin(commentId, principal.adminScopeColumn(), principal.adminScopeValue());
        return ResponseEntity.ok(SuccessResponse.of(null));
    }

    // ─── Zgłoszenia ───────────────────────────────────────────────────────────

    @GetMapping("/comments/reports")
    public ResponseEntity<SuccessResponse<AdminReportListResponse>> listReports(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        int safeSize = clampSize(size);
        int safePage = Math.max(0, page);
        Page<CommentReportResponse> result = commentService.listReports(
                status,
                principal.adminScopeColumn(),
                principal.adminScopeValue(),
                safePage, safeSize
        );
        return ResponseEntity.ok(SuccessResponse.of(new AdminReportListResponse(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        )));
    }

    /** Rozpatruje zgłoszenie — weryfikuje scope admina w serwisie. */
    @PatchMapping(value = "/comments/reports/{reportId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<Map<String, CommentReportResponse>>> reviewReport(
            @PathVariable("reportId") Long reportId,
            @RequestBody UpdateCommentReportRequest body,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        CommentReportResponse updated = commentService.reviewReport(
                reportId,
                principal.id(),
                body == null ? null : body.status(),
                body == null ? null : body.adminNote(),
                body != null && body.deleteComment(),
                principal.adminScopeColumn(),
                principal.adminScopeValue()
        );
        return ResponseEntity.ok(SuccessResponse.of(Map.of("report", updated)));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    }

    // ─── Response wrappers ────────────────────────────────────────────────────

    public record AdminCommentListResponse(
            List<CommentResponse> items,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}

    public record AdminReportListResponse(
            List<CommentReportResponse> items,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}
}
