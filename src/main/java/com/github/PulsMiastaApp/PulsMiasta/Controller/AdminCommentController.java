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

import java.util.Map;

/**
 * Panel admina — moderacja komentarzy i obsługa zgłoszeń.
 * Dostęp zabezpieczony przez SecurityConfig (/v1/admin/**).
 */
@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class AdminCommentController {

    private final PulseCommentService commentService;

    // ─── Komentarze pulsu ─────────────────────────────────────────────────────

    /**
     * Paginowana lista wszystkich komentarzy (top-level + odpowiedzi) dla danego pulsu.
     * Admin widzi też usunięte.
     */
    @GetMapping("/pulses/{pulseId}/comments")
    public ResponseEntity<SuccessResponse<AdminCommentListResponse>> listComments(
            @PathVariable("pulseId") Long pulseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        Page<CommentResponse> result = commentService.listForAdmin(pulseId, page, size);
        return ResponseEntity.ok(SuccessResponse.of(new AdminCommentListResponse(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        )));
    }

    /** Usuwa (soft-delete) dowolny komentarz w zasięgu admina. */
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<SuccessResponse<Void>> deleteComment(
            @PathVariable("commentId") Long commentId,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        commentService.deleteAsAdmin(commentId);
        return ResponseEntity.ok(SuccessResponse.of(null));
    }

    // ─── Zgłoszenia ───────────────────────────────────────────────────────────

    /**
     * Paginowana lista zgłoszeń komentarzy w zasięgu admina.
     * Parametr status: PENDING | REVIEWED | DISMISSED (null = wszystkie).
     */
    @GetMapping("/comments/reports")
    public ResponseEntity<SuccessResponse<AdminReportListResponse>> listReports(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        Page<CommentReportResponse> result = commentService.listReports(
                status,
                principal.adminScopeColumn(),
                principal.adminScopeValue(),
                page, size
        );
        return ResponseEntity.ok(SuccessResponse.of(new AdminReportListResponse(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        )));
    }

    /** Rozpatruje zgłoszenie — zatwierdza (REVIEWED) lub oddala (DISMISSED). */
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
                body != null && body.deleteComment()
        );
        return ResponseEntity.ok(SuccessResponse.of(Map.of("report", updated)));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }
    }

    // ─── Response wrappers ────────────────────────────────────────────────────

    public record AdminCommentListResponse(
            java.util.List<CommentResponse> items,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}

    public record AdminReportListResponse(
            java.util.List<CommentReportResponse> items,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}
}
