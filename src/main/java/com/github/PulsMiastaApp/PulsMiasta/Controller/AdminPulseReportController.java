package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.PulseReportResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.UpdatePulseReportRequest;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Service.PulseReportService;
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
import java.util.Set;

/**
 * Panel admina — zgłoszenia pulsów od społeczności.
 * Dostęp zabezpieczony przez SecurityConfig (/v1/admin/**).
 * Scope geograficzny przekazywany jest do serwisu z AuthPrincipal.
 */
@RestController
@RequestMapping("/v1/admin/pulse-reports")
@RequiredArgsConstructor
public class AdminPulseReportController {

    private static final int MAX_PAGE_SIZE = 100;

    private final PulseReportService pulseReportService;

    /**
     * Lista zgłoszeń w zasięgu admina, opcjonalnie filtrowana po statusie.
     * Parametr {@code status}: PENDING | REVIEWED | DISMISSED (brak = wszystkie).
     */
    @GetMapping
    public ResponseEntity<SuccessResponse<PulseReportListResponse>> listReports(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        int safeSize = clampSize(size);
        int safePage = Math.max(0, page);
        Page<PulseReportResponse> result = pulseReportService.listReports(
                status,
                principal.adminScopeColumn(),
                principal.adminScopeValues(),
                safePage, safeSize
        );
        return ResponseEntity.ok(SuccessResponse.of(new PulseReportListResponse(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        )));
    }

    /**
     * Rozpatruje zgłoszenie — weryfikuje scope admina w serwisie.
     * Body: {@code status} (REVIEWED | DISMISSED), {@code adminNote} (opcjonalny),
     * {@code rejectPulse} (jeśli true i status=REVIEWED — zmienia puls na REJECTED).
     */
    @PatchMapping(value = "/{reportId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SuccessResponse<Map<String, PulseReportResponse>>> reviewReport(
            @PathVariable("reportId") Long reportId,
            @RequestBody UpdatePulseReportRequest body,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        PulseReportResponse updated = pulseReportService.reviewReport(
                reportId,
                principal.id(),
                body == null ? null : body.status(),
                body == null ? null : body.adminNote(),
                body != null && body.rejectPulse(),
                principal.adminScopeColumn(),
                principal.adminScopeValues()
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

    public record PulseReportListResponse(
            List<PulseReportResponse> items,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}
}
