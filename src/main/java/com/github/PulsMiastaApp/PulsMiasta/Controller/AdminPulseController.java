package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ErrorResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.PulseResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Pulse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseCategory;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulsePriority;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseStatus;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Service.PulseService;
import com.github.PulsMiastaApp.PulsMiasta.Storage.PhotoStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Endpointy tylko dla użytkowników z rolą ADMIN (gate w {@code SecurityConfig}:
 * {@code /v1/admin/**}). Obsługuje paginowany listing + zmianę statusu.
 */
@RestController
@RequestMapping("/v1/admin/pulses")
@RequiredArgsConstructor
public class AdminPulseController {

    private final PulseService pulseService;
    private final PhotoStorageService photoStorageService;

    @GetMapping
    public ResponseEntity<SuccessResponse<AdminPulseListResponse>> listPulses(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "priority", required = false) String priority,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        Page<Pulse> pulses = pulseService.listForAdmin(
                parseEnum(status, PulseStatus.class, "status"),
                parseEnum(category, PulseCategory.class, "category"),
                parseEnum(priority, PulsePriority.class, "priority"),
                page, size,
                principal.adminScopeColumn(), principal.adminScopeValues());

        List<PulseResponse> items = pulses.getContent().stream()
                .map(PulseMapper::toResponse)
                .toList();

        return ResponseEntity.ok(SuccessResponse.of(new AdminPulseListResponse(
                items,
                pulses.getNumber(),
                pulses.getSize(),
                pulses.getTotalElements(),
                pulses.getTotalPages()
        )));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SuccessResponse<PulseResponse>> getPulse(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        Pulse pulse = pulseService.getAny(id);
        requirePulseInScope(pulse, principal);
        return ResponseEntity.ok(SuccessResponse.of(PulseMapper.toResponse(pulse)));
    }

    @GetMapping("/photos/{photoId}")
    public ResponseEntity<StreamingResponseBody> getPhoto(
            @PathVariable("photoId") Long photoId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        PulseService.PhotoRef ref = pulseService.resolvePhotoForAdmin(photoId);
        return PulseController.buildPhotoResponse(photoStorageService, ref, ifNoneMatch);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable("id") Long id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        requireAdmin(principal);
        Pulse existing = pulseService.getAny(id);
        requirePulseInScope(existing, principal);
        String raw = body == null ? null : body.get("status");
        PulseStatus status = parseEnum(raw, PulseStatus.class, "status");
        if (status == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.of("Missing or invalid status", "bad_request"));
        }
        Pulse pulse = pulseService.updateStatus(id, status);
        return ResponseEntity.ok(SuccessResponse.of(PulseMapper.toResponse(pulse)));
    }

    private static void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !principal.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }
    }

    private static void requirePulseInScope(Pulse pulse, AuthPrincipal principal) {
        String col = principal.adminScopeColumn();
        if (col == null) return;
        Set<String> scopeValues = principal.adminScopeValues();
        if (scopeValues.isEmpty()) return;
        String pulseVal = switch (col) {
            case "city"        -> pulse.getCity();
            case "gmina"       -> pulse.getGmina();
            case "powiat"      -> pulse.getPowiat();
            case "wojewodztwo" -> pulse.getWojewodztwo();
            default            -> null;
        };
        if (scopeValues.stream().noneMatch(v -> v.equalsIgnoreCase(pulseVal))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Pulse not in your managed area");
        }
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

    public record AdminPulseListResponse(
            List<PulseResponse> items,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}
}
