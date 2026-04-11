package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ErrorResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ReportResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Report;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportCategory;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportPriority;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ReportStatus;
import com.github.PulsMiastaApp.PulsMiasta.Service.ReportService;
import com.github.PulsMiastaApp.PulsMiasta.Storage.PhotoStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Official / urzędnik endpoints. Access is restricted to users with ROLE_ADMIN
 * (see {@code SecurityConfig#filterChain} — {@code /v1/admin/**} is gated to
 * {@code hasRole("ADMIN")}).
 *
 * <p>Listing supports optional filters: status, category, priority and standard
 * pagination (page, size).
 */
@RestController
@RequestMapping("/v1/admin/reports")
@RequiredArgsConstructor
public class AdminReportController {

    private final ReportService reportService;
    private final PhotoStorageService photoStorageService;

    @GetMapping
    public ResponseEntity<SuccessResponse<AdminReportListResponse>> listReports(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "priority", required = false) String priority,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        Page<Report> reports = reportService.listForAdmin(
                parseEnum(status, ReportStatus.class, "status"),
                parseEnum(category, ReportCategory.class, "category"),
                parseEnum(priority, ReportPriority.class, "priority"),
                page, size);

        List<ReportResponse> items = reports.getContent().stream()
                .map(ReportMapper::toResponse)
                .toList();

        return ResponseEntity.ok(SuccessResponse.of(new AdminReportListResponse(
                items,
                reports.getNumber(),
                reports.getSize(),
                reports.getTotalElements(),
                reports.getTotalPages()
        )));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SuccessResponse<ReportResponse>> getReport(@PathVariable("id") Long id) {
        Report report = reportService.getForAdmin(id);
        return ResponseEntity.ok(SuccessResponse.of(ReportMapper.toResponse(report)));
    }

    /** Stream a decrypted photo. Admins can fetch any photo regardless of ownership. */
    @GetMapping("/photos/{photoId}")
    public ResponseEntity<StreamingResponseBody> getPhoto(
            @PathVariable("photoId") Long photoId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        ReportService.PhotoRef ref = reportService.resolvePhotoForAdmin(photoId);
        return ReportController.buildPhotoResponse(photoStorageService, ref, ifNoneMatch);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable("id") Long id,
                                          @RequestBody Map<String, String> body) {
        String raw = body == null ? null : body.get("status");
        ReportStatus status = parseEnum(raw, ReportStatus.class, "status");
        if (status == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.of("Missing or invalid status", "bad_request"));
        }
        Report report = reportService.updateStatus(id, status);
        return ResponseEntity.ok(SuccessResponse.of(ReportMapper.toResponse(report)));
    }

    private <E extends Enum<E>> E parseEnum(String raw, Class<E> type, String field) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid value for " + field + ": " + raw);
        }
    }

    public record AdminReportListResponse(
            List<ReportResponse> items,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}
}
