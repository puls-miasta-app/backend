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
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulseRepository;
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
    private final PulseRepository pulseRepository;
    private final PulseFeedJdbcRepository pulseFeedJdbcRepository;
    private final UserRepository userRepository;

    // ─── Użytkownik zgłasza puls ───────────────────────────────────────────────

    @Transactional
    public void report(Long pulseId, Long reporterId, String rawReason, String description) {
        // Use JPA PulseRepository — PulseFeedJdbcRepository returns non-managed entities
        // (JDBC-mapped), which would cause "detached entity passed to persist" on save.
        Pulse pulse = pulseRepository.findById(pulseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Zgłoszenie nie znalezione"));

        User reporter = userRepository.findById(reporterId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Użytkownik nie znaleziony"));

        if (reportRepository.existsByPulseIdAndReporterId(pulseId, reporterId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "To zgłoszenie zostało już przez Ciebie zaraportowane");
        }

        PulseReportReason reason;
        try {
            reason = PulseReportReason.valueOf(rawReason.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidłowy powód zgłoszenia: " + rawReason);
        }

        if (description != null && description.length() > MAX_DESCRIPTION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Opis jest za długi (maks. " + MAX_DESCRIPTION + " znaków)");
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
        // scopeColumn == null → super admin, nie generujemy IN (puste scopeValues → 1=0 → bug S1009)
        if (scopeColumn == null) {
            Page<PulseReport> result = status == null
                    ? reportRepository.findAllReports(pageable)
                    : reportRepository.findAllReportsByStatus(status, pageable);
            return result.map(PulseReportService::toReportResponse);
        }
        // scoped admin bez przypisanych obszarów → pusty wynik bez query
        if (scopeValues.isEmpty()) {
            return Page.empty(pageable);
        }
        Page<PulseReport> result = status == null
                ? reportRepository.findInScope(scopeColumn, scopeValues, pageable)
                : reportRepository.findInScopeByStatus(status, scopeColumn, scopeValues, pageable);
        return result.map(PulseReportService::toReportResponse);
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
                    "Notatka administratora jest za długa (maks. " + MAX_ADMIN_NOTE + " znaków)");
        }

        PulseReport report = reportRepository.findByIdWithPulse(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Raport nie znaleziony"));

        requirePulseInScope(report.getPulse(), scopeColumn, scopeValues);

        PulseReportStatus newStatus = parseStatus(rawStatus);
        if (newStatus == null || newStatus == PulseReportStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Status musi wynosić REVIEWED lub DISMISSED");
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
            case "city"           -> pulse.getCity();
            case "gmina_id"       -> pulse.getGminaId()       != null ? pulse.getGminaId().toString()       : null;
            case "powiat_id"      -> pulse.getPowiatId()      != null ? pulse.getPowiatId().toString()      : null;
            case "wojewodztwo_id" -> pulse.getWojewodztwoId() != null ? pulse.getWojewodztwoId().toString() : null;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nieznana kolumna zakresu: " + scopeColumn);
        };
        if (scopeValues.stream().noneMatch(v -> v.equalsIgnoreCase(pulseVal))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Zgłoszenie nie jest w zarządzanym przez Ciebie obszarze");
        }
    }

    private static PulseReportStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return PulseReportStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidłowy status zgłoszenia: " + raw);
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
