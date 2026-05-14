package com.github.PulsMiastaApp.PulsMiasta.Service;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.CommentReportResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.CommentResponse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.CommentReport;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Pulse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulseComment;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.CommentReportReason;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.CommentReportStatus;
import com.github.PulsMiastaApp.PulsMiasta.Push.PushNotificationService;
import com.github.PulsMiastaApp.PulsMiasta.Repository.CommentLikeRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.CommentReportRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulseCommentRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulseFeedJdbcRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulseRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PulseCommentService {

    /** Placeholder wyświetlany zamiast treści usuniętego komentarza. */
    static final String DELETED_BODY = "[Usunięto]";

    private static final int MAX_BODY_LENGTH = 2000;
    private static final int MAX_DESCRIPTION = 500;
    private static final int MAX_ADMIN_NOTE  = 1000;

    private final PulseCommentRepository  commentRepository;
    private final CommentLikeRepository   likeRepository;
    private final CommentReportRepository reportRepository;
    private final PulseRepository         pulseRepository;
    private final PulseFeedJdbcRepository pulseFeedJdbcRepository;
    private final UserRepository          userRepository;
    private final JdbcTemplate            jdbcTemplate;
    private final PushNotificationService pushNotificationService;

    // ─── Odczyt ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CommentResponse> listTopLevel(Long pulseId, Long currentUserId) {
        ensurePulseExists(pulseId);
        List<PulseComment> comments =
                commentRepository.findAllByPulseIdAndParentCommentIsNullOrderByCreatedAtAsc(pulseId);
        List<PulseComment> visible = comments.stream()
                .filter(c -> c.getDeletedAt() == null)
                .toList();
        Set<Long> liked = fetchLikedIds(currentUserId, visible);
        return visible.stream()
                .map(c -> toResponse(c, liked.contains(c.getId())))
                .toList();
    }

    /**
     * Odpowiedzi na komentarz.
     * Jeśli parent jest soft-deleted — zwraca pustą listę (replies są logicznie niedostępne).
     */
    @Transactional(readOnly = true)
    public List<CommentResponse> listReplies(Long pulseId, Long commentId, Long currentUserId) {
        PulseComment parent = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Komentarz nie znaleziony"));

        // Weryfikacja że komentarz należy do podanego pulsu
        requireCommentBelongsToPulse(parent, pulseId);

        // Bug #3: parent usunięty → odpowiedzi niedostępne
        if (parent.getDeletedAt() != null) {
            return Collections.emptyList();
        }

        List<PulseComment> replies = commentRepository.findAllByParentCommentIdOrderByCreatedAtAsc(commentId)
                .stream()
                .filter(c -> c.getDeletedAt() == null)
                .toList();
        Set<Long> liked = fetchLikedIds(currentUserId, replies);
        return replies.stream()
                .map(c -> toResponse(c, liked.contains(c.getId())))
                .toList();
    }

    // ─── Tworzenie ─────────────────────────────────────────────────────────────

    @Transactional
    public CommentResponse create(Long pulseId, Long userId, String body, Long parentCommentId) {
        String trimmed = validateBody(body);
        User user = requireUser(userId);

        pulseFeedJdbcRepository.findByIdForUpdate(pulseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pulse not found"));
        Pulse pulseRef = pulseRepository.getReferenceById(pulseId);

        PulseComment c = new PulseComment();
        c.setPulse(pulseRef);
        c.setUser(user);
        c.setBody(trimmed);

        if (parentCommentId != null) {
            PulseComment parent = commentRepository.findById(parentCommentId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Komentarz nadrzędny nie znaleziony"));
            requireCommentBelongsToPulse(parent, pulseId);
            if (parent.getParentComment() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Zagnieżdżone odpowiedzi nie są obsługiwane");
            }
            if (parent.getDeletedAt() != null) {
                throw new ResponseStatusException(HttpStatus.GONE, "Nie można odpowiadać na usunięty komentarz");
            }
            c.setParentComment(parent);
            commentRepository.incrementReplyCount(parentCommentId);
        }

        commentRepository.save(c);

        if (parentCommentId == null) {
            long newCount = commentRepository.countByPulseIdAndParentCommentIsNull(pulseId);
            pulseFeedJdbcRepository.updateCommentsCount(pulseId, (int) newCount);
        }

        Long savedCommentId = c.getId();
        Long commenterId = user.getId();
        if (parentCommentId != null) {
            Long capturedParentId = parentCommentId;
            registerAfterCommit(() -> pushNotificationService.notifyCommentReply(savedCommentId, capturedParentId));
        } else {
            registerAfterCommit(() -> pushNotificationService.notifyNewComment(pulseId, savedCommentId, commenterId));
        }

        return toResponse(c, false);
    }

    private void registerAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    // ─── Edycja ────────────────────────────────────────────────────────────────

    @Transactional
    public CommentResponse edit(Long pulseId, Long commentId, Long userId, String body) {
        String trimmed = validateBody(body);
        PulseComment c = requireCommentNotDeleted(commentId);

        // Bug #2: komentarz musi należeć do podanego pulsu
        requireCommentBelongsToPulse(c, pulseId);

        if (!c.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nie można edytować komentarza innego użytkownika");
        }
        c.setBody(trimmed);
        c.setEditedAt(LocalDateTime.now());
        commentRepository.save(c);

        boolean liked = likeRepository.existsByCommentIdAndUserId(commentId, userId);
        return toResponse(c, liked);
    }

    // ─── Usuwanie (soft-delete) ────────────────────────────────────────────────

    @Transactional
    public void deleteOwn(Long pulseId, Long commentId, Long userId) {
        PulseComment c = requireCommentNotDeleted(commentId);

        // Bug #2: komentarz musi należeć do podanego pulsu
        requireCommentBelongsToPulse(c, pulseId);

        if (!c.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nie można usunąć komentarza innego użytkownika");
        }
        softDelete(c);
    }

    @Transactional
    public void deleteAsAdmin(Long commentId, String scopeColumn, Set<String> scopeValues) {
        PulseComment c = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Komentarz nie znaleziony"));
        requireCommentInScope(c.getPulse(), scopeColumn, scopeValues);
        if (c.getDeletedAt() != null) return;
        softDelete(c);
    }

    // ─── Lajki (JDBC INSERT IGNORE — bezpieczne pod concurrency) ──────────────

    @Transactional
    public CommentResponse toggleLike(Long commentId, Long userId) {
        requireCommentNotDeleted(commentId);
        requireUser(userId);

        /*
         * INSERT IGNORE zamiast check-then-act przez JPA:
         *   inserted=1 → nowy lajk  → increment
         *   inserted=0 → już istniał → DELETE → decrement (unlike)
         * Dwa równoległe żądania nie mogą oba wstawić wiersza dzięki unique constraint.
         */
        int inserted = jdbcTemplate.update(
                "INSERT IGNORE INTO comment_likes (comment_id, user_id, created_at) VALUES (?, ?, NOW())",
                commentId, userId);

        boolean nowLiked;
        if (inserted > 0) {
            commentRepository.incrementLikes(commentId);
            nowLiked = true;
        } else {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM comment_likes WHERE comment_id = ? AND user_id = ?",
                    commentId, userId);
            if (deleted > 0) commentRepository.decrementLikes(commentId);
            nowLiked = false;
        }

        // clearAutomatically=true na @Modifying — findById zwraca świeże dane
        PulseComment updated = commentRepository.findById(commentId).orElseThrow();
        return toResponse(updated, nowLiked);
    }

    // ─── Zgłoszenia ────────────────────────────────────────────────────────────

    @Transactional
    public void report(Long commentId, Long reporterId, String rawReason, String description) {
        PulseComment comment = requireCommentNotDeleted(commentId);
        User reporter = requireUser(reporterId);

        if (reportRepository.existsByCommentIdAndReporterId(commentId, reporterId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ten komentarz został już przez Ciebie zaraportowany");
        }

        CommentReportReason reason;
        try {
            reason = CommentReportReason.valueOf(rawReason.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidłowy powód zgłoszenia: " + rawReason);
        }

        if (description != null && description.length() > MAX_DESCRIPTION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Opis jest za długi (maks. " + MAX_DESCRIPTION + " znaków)");
        }

        CommentReport report = new CommentReport();
        report.setComment(commentRepository.getReferenceById(commentId));
        report.setReporter(reporter);
        report.setReason(reason);
        report.setDescription(description != null ? description.trim() : null);
        // Bug #1: snapshot treści — admin widzi oryginalną treść nawet po soft-delete
        report.setOriginalBody(comment.getBody());
        reportRepository.save(report);
    }

    // ─── Admin — lista komentarzy ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<CommentResponse> listForAdmin(Long pulseId, int page, int size) {
        ensurePulseExists(pulseId);
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").ascending());
        return commentRepository.findAllByPulseId(pulseId, pageable)
                .map(c -> toResponse(c, false));
    }

    // ─── Admin — zgłoszenia ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<CommentReportResponse> listReports(
            String rawStatus, String scopeColumn, Set<String> scopeValues, int page, int size) {
        CommentReportStatus status = parseStatus(rawStatus);
        PageRequest pageable = PageRequest.of(page, size);

        // Krok 1: paginowane ID (bez JOIN FETCH) — zob. javadoc CommentReportRepository.
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
            return new org.springframework.data.domain.PageImpl<>(
                    Collections.emptyList(), pageable, idPage.getTotalElements());
        }

        // Krok 2: doładowanie danych przez JdbcTemplate, BEZ Hibernate.
        //
        // Dlaczego nie JPA: kombinacja JPQL JOIN FETCH na CommentReport + PulseComment + Pulse
        // (lub nawet samo SELECT z `IN (?)` po pełnych encjach) na stosie Hibernate 7 /
        // Spring Boot 4 / Connector-J 9 / MySQL 9 rzuca SQLState S1009. Niezależnie od:
        // - czy jest LIMIT czy IN
        // - czy fetched-joinujemy users (BINARY column) czy nie
        // - czy zapytanie ma kolumny TEXT
        // Z natywnym SQL + JdbcTemplate omijamy cały query-builder Hibernate i ten konkretny
        // protokołowy bug.
        List<CommentReportResponse> content = loadReportResponses(ids);
        return new org.springframework.data.domain.PageImpl<>(content, pageable, idPage.getTotalElements());
    }

    /**
     * Ładuje raporty + powiązane dane przez JdbcTemplate.
     * Zachowuje kolejność wg {@code ids} (założenie: lista pochodzi z paginowanego query ORDER BY createdAt DESC).
     * Wykonuje 2 zapytania: jedno na raporty/komentarze/pulsy, drugie na e-maile użytkowników (batch po IN).
     */
    private List<CommentReportResponse> loadReportResponses(List<Long> ids) {
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        String reportsSql =
                "SELECT cr.id AS report_id, cr.admin_note, cr.original_body, cr.reason, cr.status, " +
                "       cr.reviewed_at, cr.created_at, cr.description, cr.reviewed_by_id, cr.reporter_id, " +
                "       c.id AS comment_id, c.body AS comment_body, c.deleted_at AS comment_deleted_at, " +
                "       c.user_id AS comment_user_id, p.id AS pulse_id " +
                "FROM comment_reports cr " +
                "JOIN pulse_comments c ON c.id = cr.comment_id " +
                "JOIN pulses p ON p.id = c.pulse_id " +
                "WHERE cr.id IN (" + placeholders + ")";

        List<ReportRow> rows = jdbcTemplate.query(
                reportsSql,
                ids.toArray(),
                (rs, rowNum) -> new ReportRow(
                        rs.getLong("report_id"),
                        rs.getString("admin_note"),
                        rs.getString("original_body"),
                        rs.getString("reason"),
                        rs.getString("status"),
                        rs.getTimestamp("reviewed_at"),
                        rs.getTimestamp("created_at"),
                        rs.getString("description"),
                        (Long) rs.getObject("reviewed_by_id"),
                        rs.getLong("reporter_id"),
                        rs.getLong("comment_id"),
                        rs.getString("comment_body"),
                        rs.getTimestamp("comment_deleted_at") != null,
                        (Long) rs.getObject("comment_user_id"),
                        rs.getLong("pulse_id")
                )
        );

        // Zbierz wszystkie user IDs (reporter / comment author / reviewer) i pobierz e-maile w jednym query.
        java.util.Set<Long> userIds = new java.util.HashSet<>();
        for (ReportRow r : rows) {
            userIds.add(r.reporterId());
            if (r.commentUserId() != null) userIds.add(r.commentUserId());
            if (r.reviewedById() != null) userIds.add(r.reviewedById());
        }
        java.util.Map<Long, String> emailById = userIds.isEmpty()
                ? java.util.Map.of()
                : jdbcTemplate.query(
                        "SELECT id, email FROM users WHERE id IN ("
                                + userIds.stream().map(id -> "?").collect(Collectors.joining(",")) + ")",
                        userIds.toArray(),
                        rs -> {
                            java.util.Map<Long, String> m = new java.util.HashMap<>();
                            while (rs.next()) m.put(rs.getLong("id"), rs.getString("email"));
                            return m;
                        });

        java.util.Map<Long, CommentReportResponse> byId = new java.util.HashMap<>();
        for (ReportRow r : rows) {
            String commentBody = r.commentDeleted() ? DELETED_BODY : r.commentBody();
            byId.put(r.reportId(), new CommentReportResponse(
                    String.valueOf(r.reportId()),
                    String.valueOf(r.commentId()),
                    String.valueOf(r.pulseId()),
                    commentBody,
                    r.originalBody(),
                    r.reporterId(),
                    emailById.get(r.reporterId()),
                    r.commentUserId(),
                    r.commentUserId() != null ? emailById.get(r.commentUserId()) : null,
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

        // Zachowaj kolejność z idPage.
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    private record ReportRow(
            Long reportId,
            String adminNote,
            String originalBody,
            String reason,
            String status,
            java.sql.Timestamp reviewedAt,
            java.sql.Timestamp createdAt,
            String description,
            Long reviewedById,
            Long reporterId,
            Long commentId,
            String commentBody,
            boolean commentDeleted,
            Long commentUserId,
            Long pulseId
    ) {}

    /**
     * Rozpatruje zgłoszenie.
     * Security fix: ładujemy raport razem z komentarzem i pulsem w jednym zapytaniu,
     * weryfikujemy scope na załadowanej encji — eliminuje TOCTOU existsByIdInScope + findById.
     */
    @Transactional
    public CommentReportResponse reviewReport(Long reportId, Long adminId,
                                               String rawStatus, String adminNote,
                                               boolean deleteComment,
                                               String scopeColumn, Set<String> scopeValues) {
        if (adminNote != null && adminNote.length() > MAX_ADMIN_NOTE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Notatka administratora jest za długa (maks. " + MAX_ADMIN_NOTE + " znaków)");
        }

        // findById + lazy load comment/pulse zamiast JOIN FETCH — JOIN FETCH na cr+c+p
        // na Hibernate 7 / Connector-J 9 / MySQL 9 rzuca SQLState S1009 (zob. komentarze
        // w CommentReportRepository). Lazy single-table SELECT-y są bezpieczne.
        // Brak TOCTOU: scope sprawdzamy na encji załadowanej w tej samej tx.
        CommentReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Raport nie znaleziony"));

        // Scope check na załadowanej encji — bez dodatkowego query
        requireCommentInScope(report.getComment().getPulse(), scopeColumn, scopeValues);

        CommentReportStatus newStatus = parseStatus(rawStatus);
        if (newStatus == null || newStatus == CommentReportStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Status musi wynosić REVIEWED lub DISMISSED");
        }

        report.setStatus(newStatus);
        report.setAdminNote(adminNote);
        report.setReviewedBy(userRepository.getReferenceById(adminId));
        report.setReviewedAt(LocalDateTime.now());
        reportRepository.save(report);

        if (deleteComment && newStatus == CommentReportStatus.REVIEWED) {
            softDelete(report.getComment());
        }

        return toReportResponse(report);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private void softDelete(PulseComment c) {
        c.setDeletedAt(LocalDateTime.now());
        c.setBody(DELETED_BODY);
        commentRepository.save(c);

        Long parentId = c.getParentComment() != null ? c.getParentComment().getId() : null;
        if (parentId != null) {
            commentRepository.decrementReplyCount(parentId);
        } else {
            long newCount = commentRepository.countByPulseIdAndParentCommentIsNullAndDeletedAtIsNull(c.getPulse().getId());
            pulseFeedJdbcRepository.updateCommentsCount(c.getPulse().getId(), (int) newCount);
        }
    }

    private String validateBody(String body) {
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Treść komentarza jest wymagana");
        }
        String trimmed = body.trim();
        if (trimmed.length() > MAX_BODY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Komentarz jest za długi (maks. " + MAX_BODY_LENGTH + " znaków)");
        }
        return trimmed;
    }

    private PulseComment requireCommentNotDeleted(Long commentId) {
        PulseComment c = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Komentarz nie znaleziony"));
        if (c.getDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.GONE, "Komentarz został usunięty");
        }
        return c;
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Użytkownik nie znaleziony"));
    }

    private void ensurePulseExists(Long pulseId) {
        if (!pulseFeedJdbcRepository.existsById(pulseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Zgłoszenie nie znalezione");
        }
    }

    /** Bug #2: weryfikuje że komentarz faktycznie należy do podanego pulsu. */
    private static void requireCommentBelongsToPulse(PulseComment c, Long pulseId) {
        if (!c.getPulse().getId().equals(pulseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Komentarz nie należy do tego zgłoszenia");
        }
    }

    private Set<Long> fetchLikedIds(Long userId, List<PulseComment> comments) {
        if (userId == null || comments.isEmpty()) return Collections.emptySet();
        Set<Long> ids = comments.stream().map(PulseComment::getId).collect(Collectors.toSet());
        return likeRepository.findLikedCommentIds(userId, ids);
    }

    private static void requireCommentInScope(Pulse pulse, String scopeColumn, Set<String> scopeValues) {
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
                    "Zgłoszenie komentarza nie jest w zarządzanym przez Ciebie obszarze");
        }
    }

    private static CommentReportStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return CommentReportStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidłowy status zgłoszenia: " + raw);
        }
    }

    // ─── Mapowanie ─────────────────────────────────────────────────────────────

    public static CommentResponse toResponse(PulseComment c, boolean userLiked) {
        User u = c.getUser();
        boolean deleted = c.getDeletedAt() != null;
        String body = deleted ? DELETED_BODY : c.getBody();
        Long parentId = c.getParentComment() != null ? c.getParentComment().getId() : null;
        return new CommentResponse(
                String.valueOf(c.getId()),
                String.valueOf(c.getPulse().getId()),
                !deleted && u != null ? u.getId()        : null,
                !deleted && u != null ? u.getEmail()     : null,
                !deleted && u != null ? u.getFirstName() : null,
                !deleted && u != null ? u.getLastName()  : null,
                body,
                c.getCreatedAt() != null ? c.getCreatedAt().toString() : null,
                c.getEditedAt()  != null ? c.getEditedAt().toString()  : null,
                parentId != null ? String.valueOf(parentId) : null,
                c.getLikesCount(),
                c.getReplyCount(),
                userLiked,
                deleted
        );
    }

    private static CommentReportResponse toReportResponse(CommentReport r) {
        PulseComment c      = r.getComment();
        User reporter       = r.getReporter();
        User reviewer       = r.getReviewedBy();
        User commentAuthor  = c.getUser();
        return new CommentReportResponse(
                String.valueOf(r.getId()),
                String.valueOf(c.getId()),
                String.valueOf(c.getPulse().getId()),
                c.getBody(),
                r.getOriginalBody(),
                reporter      != null ? reporter.getId()      : null,
                reporter      != null ? reporter.getEmail()   : null,
                commentAuthor != null ? commentAuthor.getId()    : null,
                commentAuthor != null ? commentAuthor.getEmail() : null,
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
