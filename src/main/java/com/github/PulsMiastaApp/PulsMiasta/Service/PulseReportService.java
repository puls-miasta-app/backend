package com.github.PulsMiastaApp.PulsMiasta.Service;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.PulseReportResponse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Pulse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulseReport;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseReportReason;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseReportStatus;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseStatus;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulseFeedJdbcRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulseReportRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PulseReportService {

    private static final int MAX_DESCRIPTION = 1000;
    private static final int MAX_ADMIN_NOTE = 2000;

    private final PulseReportRepository reportRepository;
    private final PulseFeedJdbcRepository pulseFeedJdbcRepository;
    private final UserRepository userRepository;

    // ─── Użytkownik zgłasza puls ───────────────────────────────────────────────

    @Transactional
    public void report(Long pulseId, Long reporterId, String rawReason, String description) {
        Pulse pulse = pulseFeedJdbcRepository.findByIdWithPhotos(pulseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pulse not found"));

        User reporter = userRepository.findById(reporterId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if (reportRepository.existsByPulseIdAndReporterId(pulseId, reporterId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You have already reported this pulse");
        }

        PulseReportReason reason;
        try {
            reason = PulseReportReason.valueOf(rawReason.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report reason: " + rawReason);
        }

        if (description != null && description.length() > MAX_DESCRIPTION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Description too long (max " + MAX_DESCRIPTION + " chars)");
        }

        PulseReport report = new PulseReport();
        report.setPulse(pulse);
        report.setReporter(reporter);
        report.setReason(reason);
        report.setDescription(description != null ? description.trim() : null);
        reportRepository.save(report);
    }

    // ─── Admin — lista zgłoszeń ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<PulseReportResponse> listReports(
            String rawStatus, String scopeColumn, Set<String> scopeValues, int page, int size) {
        PulseReportStatus status = parseStatus(rawStatus);
        PageRequest pageable = PageRequest.of(page, size);
        return reportRepository.findInScope(status, scopeColumn, scopeValues, pageable)
                .map(PulseReportService::toReportResponse);
    }

    /**
     * Rozpatruje zgłoszenie.
     * Ładujemy raport razem z pulsem w jednym zapytaniu, weryfikujemy scope na
     * załadowanej encji — eliminuje TOCTOU existsByIdInScope + findById.
     */
    @Transactional
    public PulseReportResponse reviewReport(Long reportId, Long adminId,
                                             String rawStatus, String adminNote,
                                             boolean rejectPulse,
                                             String scopeColumn, Set<String> scopeValues) {
        if (adminNote != null && adminNote.length() > MAX_ADMIN_NOTE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Admin note too long (max " + MAX_ADMIN_NOTE + " chars)");
        }

        PulseReport report = reportRepository.findByIdWithPulse(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found"));

        requirePulseInScope(report.getPulse(), scopeColumn, scopeValues);

        PulseReportStatus newStatus = parseStatus(rawStatus);
        if (newStatus == null || newStatus == PulseReportStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Status must be REVIEWED or DISMISSED");
        }

        report.setStatus(newStatus);
        report.setAdminNote(adminNote);
        report.setReviewedBy(userRepository.getReferenceById(adminId));
        report.setReviewedAt(LocalDateTime.now());
        reportRepository.save(report);

        if (rejectPulse && newStatus == PulseReportStatus.REVIEWED) {
            pulseFeedJdbcRepository.updateStatusById(report.getPulse().getId(), PulseStatus.REJECTED.name());
        }

        return toReportResponse(report);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private static void requirePulseInScope(Pulse pulse, String scopeColumn, Set<String> scopeValues) {
        if (scopeColumn == null || scopeValues == null || scopeValues.isEmpty()) return;
        String pulseVal = switch (scopeColumn) {
            case "city"        -> pulse.getCity();
            case "gmina"       -> pulse.getGmina();
            case "powiat"      -> pulse.getPowiat();
            case "wojewodztwo" -> pulse.getWojewodztwo();
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown scope column: " + scopeColumn);
        };
        if (scopeValues.stream().noneMatch(v -> v.equalsIgnoreCase(pulseVal))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Pulse is not in your managed area");
        }
    }

    private static PulseReportStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return PulseReportStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report status: " + raw);
        }
    }

    // ─── Mapowanie ─────────────────────────────────────────────────────────────

    private static PulseReportResponse toReportResponse(PulseReport r) {
        Pulse p        = r.getPulse();
        User reporter  = r.getReporter();
        User reviewer  = r.getReviewedBy();
        return new PulseReportResponse(
                String.valueOf(r.getId()),
                String.valueOf(p.getId()),
                p.getTitle(),
                p.getCity(),
                p.getDistrict(),
                reporter != null ? reporter.getId()    : null,
                reporter != null ? reporter.getEmail() : null,
                r.getReason().name(),
                r.getDescription(),
                r.getStatus().name(),
                r.getAdminNote(),
                reviewer != null ? reviewer.getId()    : null,
                reviewer != null ? reviewer.getEmail() : null,
                r.getReviewedAt() != null ? r.getReviewedAt().toString() : null,
                r.getCreatedAt()  != null ? r.getCreatedAt().toString()  : null
        );
    }
}
