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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found"));

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
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent comment not found"));
            requireCommentBelongsToPulse(parent, pulseId);
            if (parent.getParentComment() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nested replies are not supported");
            }
            if (parent.getDeletedAt() != null) {
                throw new ResponseStatusException(HttpStatus.GONE, "Cannot reply to a deleted comment");
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
    public void deleteOwn(Long pulseId, Long commentId, Long userId) {
        PulseComment c = requireCommentNotDeleted(commentId);

        // Bug #2: komentarz musi należeć do podanego pulsu
        requireCommentBelongsToPulse(c, pulseId);

        if (!c.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete another user's comment");
        }
        softDelete(c);
    }

    @Transactional
    public void deleteAsAdmin(Long commentId, String scopeColumn, Set<String> scopeValues) {
        PulseComment c = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found"));
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
        return reportRepository.findInScope(status, scopeColumn, scopeValues, pageable)
                .map(PulseCommentService::toReportResponse);
    }

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
                    "Admin note too long (max " + MAX_ADMIN_NOTE + " chars)");
        }

        // JOIN FETCH comment + pulse w jednym zapytaniu → brak TOCTOU
        CommentReport report = reportRepository.findByIdWithCommentAndPulse(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found"));

        // Scope check na załadowanej encji — bez dodatkowego query
        requireCommentInScope(report.getComment().getPulse(), scopeColumn, scopeValues);

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
            long newCount = commentRepository.countByPulseIdAndParentCommentIsNullAndDeletedAtIsNull(c.getPulse().getId());
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

    /** Bug #2: weryfikuje że komentarz faktycznie należy do podanego pulsu. */
    private static void requireCommentBelongsToPulse(PulseComment c, Long pulseId) {
        if (!c.getPulse().getId().equals(pulseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comment not found in this pulse");
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
            case "city"        -> pulse.getCity();
            case "gmina"       -> pulse.getGmina();
            case "powiat"      -> pulse.getPowiat();
            case "wojewodztwo" -> pulse.getWojewodztwo();
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown scope column: " + scopeColumn);
        };
        if (scopeValues.stream().noneMatch(v -> v.equalsIgnoreCase(pulseVal))) {
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
        PulseComment c = r.getComment();
        User reporter  = r.getReporter();
        User reviewer  = r.getReviewedBy();
        return new CommentReportResponse(
                String.valueOf(r.getId()),
                String.valueOf(c.getId()),
                String.valueOf(c.getPulse().getId()),
                c.getBody(),
                r.getOriginalBody(),           // snapshot z momentu zgłoszenia
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
