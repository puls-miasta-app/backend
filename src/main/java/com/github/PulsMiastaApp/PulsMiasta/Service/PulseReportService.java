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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PulseReportService {

    private static final int MAX_DESCRIPTION = 1000;
    private static final int MAX_ADMIN_NOTE = 2000;

    private final PulseReportRepository reportRepository;
    private final PulseRepository pulseRepository;
    private final PulseFeedJdbcRepository pulseFeedJdbcRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

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

        // Krok 1: paginowane ID przez JPA (zob. javadoc PulseReportRepository).
        Page<Long> idPage;
        if (scopeColumn == null) {
            idPage = status == null
                    ? reportRepository.findAllReportIds(pageable)
                    : reportRepository.findReportIdsByStatus(status, pageable);
        } else if (scopeValues.isEmpty()) {
            return Page.empty(pageable);
        } else {
            idPage = status == null
                    ? reportRepository.findReportIdsInScope(scopeColumn, scopeValues, pageable)
                    : reportRepository.findReportIdsInScopeByStatus(status, scopeColumn, scopeValues, pageable);
        }

        List<Long> ids = idPage.getContent();
        if (ids.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), pageable, idPage.getTotalElements());
        }

        // Krok 2: doładowanie wierszy natywnym SQL — JPQL JOIN FETCH rzuca S1009.
        List<PulseReportResponse> content = loadReportResponses(ids);
        return new PageImpl<>(content, pageable, idPage.getTotalElements());
    }

    /**
     * Ładuje raporty pulsów + e-maile użytkowników przez JdbcTemplate.
     * Zachowuje kolejność wg {@code ids} (paginowane ORDER BY createdAt DESC).
     */
    private List<PulseReportResponse> loadReportResponses(List<Long> ids) {
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        String reportsSql =
                "SELECT pr.id AS report_id, pr.reason, pr.description, pr.status, pr.admin_note, " +
                "       pr.reviewed_at, pr.created_at, pr.reviewed_by_id, pr.reporter_id, " +
                "       p.id AS pulse_id, p.title AS pulse_title, p.city AS pulse_city, p.district AS pulse_district " +
                "FROM pulse_reports pr " +
                "JOIN pulses p ON p.id = pr.pulse_id " +
                "WHERE pr.id IN (" + placeholders + ")";

        List<ReportRow> rows = jdbcTemplate.query(
                reportsSql,
                ids.toArray(),
                (rs, rowNum) -> new ReportRow(
                        rs.getLong("report_id"),
                        rs.getString("reason"),
                        rs.getString("description"),
                        rs.getString("status"),
                        rs.getString("admin_note"),
                        rs.getTimestamp("reviewed_at"),
                        rs.getTimestamp("created_at"),
                        (Long) rs.getObject("reviewed_by_id"),
                        rs.getLong("reporter_id"),
                        rs.getLong("pulse_id"),
                        rs.getString("pulse_title"),
                        rs.getString("pulse_city"),
                        rs.getString("pulse_district")
                )
        );

        Set<Long> userIds = new HashSet<>();
        for (ReportRow r : rows) {
            userIds.add(r.reporterId());
            if (r.reviewedById() != null) userIds.add(r.reviewedById());
        }
        Map<Long, String> emailById = userIds.isEmpty()
                ? Map.of()
                : jdbcTemplate.query(
                        "SELECT id, email FROM users WHERE id IN ("
                                + userIds.stream().map(id -> "?").collect(Collectors.joining(",")) + ")",
                        userIds.toArray(),
                        rs -> {
                            Map<Long, String> m = new HashMap<>();
                            while (rs.next()) m.put(rs.getLong("id"), rs.getString("email"));
                            return m;
                        });

        Map<Long, PulseReportResponse> byId = new HashMap<>();
        for (ReportRow r : rows) {
            byId.put(r.reportId(), new PulseReportResponse(
                    String.valueOf(r.reportId()),
                    String.valueOf(r.pulseId()),
                    r.pulseTitle(),
                    r.pulseCity(),
                    r.pulseDistrict(),
                    r.reporterId(),
                    emailById.get(r.reporterId()),
                    r.reason(),
                    r.description(),
                    r.status(),
                    r.adminNote(),
                    r.reviewedById(),
                    r.reviewedById() != null ? emailById.get(r.reviewedById()) : null,
                    r.reviewedAt() != null ? r.reviewedAt().toLocalDateTime().toString() : null,
                    r.createdAt() != null ? r.createdAt().toLocalDateTime().toString() : null
            ));
        }

        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private record ReportRow(
            Long reportId,
            String reason,
            String description,
            String status,
            String adminNote,
            java.sql.Timestamp reviewedAt,
            java.sql.Timestamp createdAt,
            Long reviewedById,
            Long reporterId,
            Long pulseId,
            String pulseTitle,
            String pulseCity,
            String pulseDistrict
    ) {}

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

        // findById + lazy load pulse — JPQL JOIN FETCH PulseReport+Pulse+User rzuca S1009.
        // Lazy single-table SELECT-y są bezpieczne, brak TOCTOU bo scope sprawdzany na encji w tej samej tx.
        PulseReport report = reportRepository.findById(reportId)
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
