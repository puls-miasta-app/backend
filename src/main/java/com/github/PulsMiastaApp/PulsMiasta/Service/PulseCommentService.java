package com.github.PulsMiastaApp.PulsMiasta.Service;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.CommentReportResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.CommentResponse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.CommentReport;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Pulse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulseComment;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.CommentReportReason;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.CommentReportStatus;
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

    private static final int MAX_BODY_LENGTH   = 2000;
    private static final int MAX_DESCRIPTION   = 500;
    private static final int MAX_ADMIN_NOTE    = 1000;

    private final PulseCommentRepository commentRepository;
    private final CommentLikeRepository  likeRepository;
    private final CommentReportRepository reportRepository;
    private final PulseRepository        pulseRepository;
    private final PulseFeedJdbcRepository pulseFeedJdbcRepository;
    private final UserRepository         userRepository;
    private final JdbcTemplate           jdbcTemplate;

    // ─── Odczyt ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CommentResponse> listTopLevel(Long pulseId, Long currentUserId) {
        ensurePulseExists(pulseId);
        // JOIN FETCH c.user w repozytorium — brak N+1
        List<PulseComment> comments =
                commentRepository.findAllByPulseIdAndParentCommentIsNullOrderByCreatedAtAsc(pulseId);
        Set<Long> liked = fetchLikedIds(currentUserId, comments);
        return comments.stream()
                .map(c -> toResponse(c, liked.contains(c.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> listReplies(Long commentId, Long currentUserId) {
        // JOIN FETCH c.user w repozytorium — brak N+1
        List<PulseComment> replies =
                commentRepository.findAllByParentCommentIdOrderByCreatedAtAsc(commentId);
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

        // JDBC SELECT FOR UPDATE — JPA @Lock(@PESSIMISTIC_WRITE) wali S1009 na tabeli pulses
        pulseFeedJdbcRepository.findByIdForUpdate(pulseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pulse not found"));
        Pulse pulseRef = pulseRepository.getReferenceById(pulseId);

        PulseComment c = new PulseComment();
        c.setPulse(pulseRef);
        c.setUser(user);
        c.setBody(trimmed);

        if (parentCommentId != null) {
            PulseComment parent = commentRepository.findById(parentCommentId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent comment not found"));
            if (!parent.getPulse().getId().equals(pulseId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parent comment belongs to a different pulse");
            }
            if (parent.getParentComment() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nested replies are not supported");
            }
            c.setParentComment(parent);
            commentRepository.incrementReplyCount(parentCommentId);
        }

        commentRepository.save(c);

        if (parentCommentId == null) {
            long newCount = commentRepository.countByPulseIdAndParentCommentIsNull(pulseId);
            pulseFeedJdbcRepository.updateCommentsCount(pulseId, (int) newCount);
        }

        return toResponse(c, false);
    }

    // ─── Edycja ────────────────────────────────────────────────────────────────

    @Transactional
    public CommentResponse edit(Long commentId, Long userId, String body) {
        String trimmed = validateBody(body);
        PulseComment c = requireCommentNotDeleted(commentId);

        if (!c.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot edit another user's comment");
        }
        c.setBody(trimmed);
        c.setEditedAt(LocalDateTime.now());
        commentRepository.save(c);

        boolean liked = likeRepository.existsByCommentIdAndUserId(commentId, userId);
        return toResponse(c, liked);
    }

    // ─── Usuwanie (soft-delete) ────────────────────────────────────────────────

    @Transactional
    public void deleteOwn(Long commentId, Long userId) {
        PulseComment c = requireCommentNotDeleted(commentId);
        if (!c.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete another user's comment");
        }
        softDelete(c);
    }

    /**
     * Usuwa komentarz jako admin — weryfikuje scope geograficzny.
     *
     * @param scopeColumn null dla SUPER_ADMIN (brak filtru)
     * @param scopeValue  wartość zakresu admina
     */
    @Transactional
    public void deleteAsAdmin(Long commentId, String scopeColumn, String scopeValue) {
        // Ładujemy komentarz razem z pulsem (JOIN FETCH) by sprawdzić scope
        PulseComment c = commentRepository.findByIdWithPulse(commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found"));

        requireCommentInScope(c.getPulse(), scopeColumn, scopeValue);

        if (c.getDeletedAt() != null) return; // już usunięty — idempotentne
        softDelete(c);
    }

    // ─── Lajki (JDBC INSERT IGNORE — bezpieczne pod concurrency) ──────────────

    @Transactional
    public CommentResponse toggleLike(Long commentId, Long userId) {
        requireCommentNotDeleted(commentId);
        requireUser(userId); // weryfikacja że użytkownik istnieje

        /*
         * Używamy JDBC INSERT IGNORE zamiast check-then-act przez JPA.
         * Dzięki temu dwa równoległe żądania "like" nie mogą oba wykonać
         * INSERT — MySQL odrzuca drugi cicho (IGNORE). Każdy request
         * atomowo decyduje czy polubił czy odpolubił:
         *   inserted=1 → nowy lajk     → increment
         *   inserted=0 → już istniał   → DELETE → decrement (unlike)
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
            if (deleted > 0) {
                commentRepository.decrementLikes(commentId);
            }
            nowLiked = false;
        }

        // clearAutomatically=true na @Modifying — fetch daje świeże dane
        PulseComment updated = commentRepository.findById(commentId).orElseThrow();
        return toResponse(updated, nowLiked);
    }

    // ─── Zgłoszenia ────────────────────────────────────────────────────────────

    @Transactional
    public void report(Long commentId, Long reporterId, String rawReason, String description) {
        requireCommentNotDeleted(commentId);
        User reporter = requireUser(reporterId);

        if (reportRepository.existsByCommentIdAndReporterId(commentId, reporterId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You have already reported this comment");
        }

        CommentReportReason reason;
        try {
            reason = CommentReportReason.valueOf(rawReason.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report reason: " + rawReason);
        }

        if (description != null && description.length() > MAX_DESCRIPTION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Description too long (max " + MAX_DESCRIPTION + " chars)");
        }

        CommentReport report = new CommentReport();
        report.setComment(commentRepository.getReferenceById(commentId));
        report.setReporter(reporter);
        report.setReason(reason);
        report.setDescription(description != null ? description.trim() : null);
        reportRepository.save(report);
    }

    // ─── Admin — lista komentarzy ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<CommentResponse> listForAdmin(Long pulseId, int page, int size) {
        ensurePulseExists(pulseId);
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").ascending());
        // @EntityGraph(attributePaths={"user"}) na repozytorium eliminuje N+1
        return commentRepository.findAllByPulseId(pulseId, pageable)
                .map(c -> toResponse(c, false));
    }

    // ─── Admin — zgłoszenia ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<CommentReportResponse> listReports(
            String rawStatus, String scopeColumn, String scopeValue, int page, int size) {
        CommentReportStatus status = parseStatus(rawStatus);
        PageRequest pageable = PageRequest.of(page, size);
        // JOIN FETCH reporter, reviewedBy, comment, pulse w repozytorium — brak N+1
        return reportRepository.findInScope(status, scopeColumn, scopeValue, pageable)
                .map(PulseCommentService::toReportResponse);
    }

    /**
     * Rozpatruje zgłoszenie — weryfikuje scope admina przed zapisem.
     *
     * @param scopeColumn null dla SUPER_ADMIN
     */
    @Transactional
    public CommentReportResponse reviewReport(Long reportId, Long adminId,
                                               String rawStatus, String adminNote,
                                               boolean deleteComment,
                                               String scopeColumn, String scopeValue) {
        if (adminNote != null && adminNote.length() > MAX_ADMIN_NOTE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Admin note too long (max " + MAX_ADMIN_NOTE + " chars)");
        }

        // Weryfikacja scope — admin może rozpatrywać tylko zgłoszenia w swoim zasięgu
        if (scopeColumn != null && !reportRepository.existsByIdInScope(reportId, scopeColumn, scopeValue)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Report not in your managed area");
        }

        CommentReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found"));

        CommentReportStatus newStatus = parseStatus(rawStatus);
        if (newStatus == null || newStatus == CommentReportStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Status must be REVIEWED or DISMISSED");
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
            long newCount = commentRepository.countByPulseIdAndParentCommentIsNull(c.getPulse().getId());
            pulseFeedJdbcRepository.updateCommentsCount(c.getPulse().getId(), (int) newCount);
        }
    }

    private String validateBody(String body) {
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comment body is required");
        }
        String trimmed = body.trim();
        if (trimmed.length() > MAX_BODY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Comment too long (max " + MAX_BODY_LENGTH + " chars)");
        }
        return trimmed;
    }

    private PulseComment requireCommentNotDeleted(Long commentId) {
        PulseComment c = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found"));
        if (c.getDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.GONE, "Comment has been deleted");
        }
        return c;
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private void ensurePulseExists(Long pulseId) {
        if (!pulseFeedJdbcRepository.existsById(pulseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pulse not found");
        }
    }

    private Set<Long> fetchLikedIds(Long userId, List<PulseComment> comments) {
        if (userId == null || comments.isEmpty()) return Collections.emptySet();
        Set<Long> ids = comments.stream().map(PulseComment::getId).collect(Collectors.toSet());
        return likeRepository.findLikedCommentIds(userId, ids);
    }

    private static void requireCommentInScope(Pulse pulse, String scopeColumn, String scopeValue) {
        if (scopeColumn == null) return; // SUPER_ADMIN — brak filtru
        String pulseVal = switch (scopeColumn) {
            case "city"        -> pulse.getCity();
            case "gmina"       -> pulse.getGmina();
            case "powiat"      -> pulse.getPowiat();
            case "wojewodztwo" -> pulse.getWojewodztwo();
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown scope column: " + scopeColumn);
        };
        if (scopeValue != null && !scopeValue.equalsIgnoreCase(pulseVal)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Comment's pulse is not in your managed area");
        }
    }

    private static CommentReportStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return CommentReportStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report status: " + raw);
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
        PulseComment c   = r.getComment();
        User reporter    = r.getReporter();
        User reviewer    = r.getReviewedBy();
        return new CommentReportResponse(
                String.valueOf(r.getId()),
                String.valueOf(c.getId()),
                String.valueOf(c.getPulse().getId()),
                c.getBody(),
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
