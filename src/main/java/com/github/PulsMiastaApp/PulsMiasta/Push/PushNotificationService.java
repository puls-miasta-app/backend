package com.github.PulsMiastaApp.PulsMiasta.Push;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.NotificationPreferences;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Pulse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.PulseComment;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ChatThreadStatus;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.PulseStatus;
import com.github.PulsMiastaApp.PulsMiasta.Repository.ChatMessageRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.ChatThreadRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.DeviceRegistrationRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.NotificationPreferencesRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulseCommentRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulseFeedJdbcRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Wysyła powiadomienia push do użytkowników.
 * Każda metoda jest @Async — wywoływana po commicie transakcji, nie blokuje callera.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushNotificationService {

    private final ExpoPushService expoPushService;
    private final DeviceRegistrationRepository deviceRepo;
    private final NotificationPreferencesRepository prefsRepo;
    private final PulseFeedJdbcRepository pulseFeedJdbcRepository;
    private final PulseCommentRepository commentRepository;
    private final ChatThreadRepository chatThreadRepository;
    private final ChatMessageRepository chatMessageRepository;

    /** Powiadamia właściciela pulsa o zmianie statusu przez admina. */
    @Async("photoUploadExecutor")
    @Transactional(readOnly = true)
    public void notifyStatusChange(Long pulseId, PulseStatus newStatus) {
        Pulse pulse = pulseFeedJdbcRepository.findByIdWithPhotos(pulseId).orElse(null);
        if (pulse == null || pulse.getUser() == null) return;

        Long ownerId = pulse.getUser().getId();
        if (!canReceive(ownerId, PrefType.STATUS_UPDATES)) return;

        List<String> tokens = tokensFor(ownerId);

        if (tokens.isEmpty()) return;

        String title = "Aktualizacja zgłoszenia";
        String body = "Twoje zgłoszenie \"" + truncate(pulse.getTitle(), 50) + "\" — " + newStatus.label();
        expoPushService.send(tokens, title, body, Map.of(
                "pulseId", pulseId,
                "type", "STATUS_CHANGE",
                "status", newStatus.name()
        ));
    }

    /** Powiadamia właściciela pulsa że zostało podpięte pod istniejące zgłoszenie. */
    @Async("photoUploadExecutor")
    @Transactional(readOnly = true)
    public void notifyMerged(Long ownerId, Long primaryPulseId) {
        if (!canReceive(ownerId, PrefType.STATUS_UPDATES)) return;
        List<String> tokens = tokensFor(ownerId);
        if (tokens.isEmpty()) return;

        Pulse primary = pulseFeedJdbcRepository.findByIdWithPhotos(primaryPulseId).orElse(null);
        String primaryTitle = primary != null ? truncate(primary.getTitle(), 50) : "inne zgłoszenie";

        expoPushService.send(tokens,
                "Zgłoszenie podpięte",
                "Twoje zgłoszenie dotyczy tego samego problemu co \"" + primaryTitle + "\". Możesz śledzić je tam.",
                Map.of("pulseId", primaryPulseId, "type", "MERGED")
        );
    }

    /** Powiadamia właściciela pulsa o nowym komentarzu (gdy ktoś inny komentuje). */
    @Async("photoUploadExecutor")
    @Transactional(readOnly = true)
    public void notifyNewComment(Long pulseId, Long commentId, Long commenterId) {
        Pulse pulse = pulseFeedJdbcRepository.findByIdWithPhotos(pulseId).orElse(null);
        if (pulse == null || pulse.getUser() == null) return;

        Long ownerId = pulse.getUser().getId();
        if (ownerId.equals(commenterId)) return; // właściciel skomentował własne zgłoszenie
        if (!canReceive(ownerId, PrefType.COMMENT_REPLIES)) return;

        List<String> tokens = tokensFor(ownerId);
        if (tokens.isEmpty()) return;

        PulseComment comment = commentRepository.findByIdWithUser(commentId).orElse(null);
        String commenterName = comment != null ? displayName(comment.getUser()) : "Ktoś";

        expoPushService.send(tokens,
                commenterName + " skomentował Twoje zgłoszenie",
                truncate(comment != null ? comment.getBody() : "", 100),
                Map.of("pulseId", pulseId, "commentId", commentId, "type", "NEW_COMMENT")
        );
    }

    /** Powiadamia autora komentarza o odpowiedzi (gdy ktoś inny odpowiada). */
    @Async("photoUploadExecutor")
    @Transactional(readOnly = true)
    public void notifyCommentReply(Long replyId, Long parentCommentId) {
        PulseComment parent = commentRepository.findByIdWithUser(parentCommentId).orElse(null);
        if (parent == null || parent.getUser() == null) return;

        PulseComment reply = commentRepository.findByIdWithUser(replyId).orElse(null);
        if (reply == null) return;

        Long parentAuthorId = parent.getUser().getId();
        Long replierId = reply.getUser() != null ? reply.getUser().getId() : null;
        if (parentAuthorId.equals(replierId)) return; // odpowiedział sam sobie
        if (!canReceive(parentAuthorId, PrefType.COMMENT_REPLIES)) return;

        List<String> tokens = tokensFor(parentAuthorId);
        if (tokens.isEmpty()) return;

        String replierName = displayName(reply.getUser());
        Long pulseId = parent.getPulse() != null ? parent.getPulse().getId() : null;

        expoPushService.send(tokens,
                replierName + " odpowiedział na Twój komentarz",
                truncate(reply.getBody(), 100),
                pulseId != null
                        ? Map.of("pulseId", pulseId, "commentId", replyId, "type", "COMMENT_REPLY")
                        : Map.of("commentId", replyId, "type", "COMMENT_REPLY")
        );
    }

    private List<String> tokensFor(Long userId) {
        return deviceRepo.findAllByUserId(userId).stream()
                .map(d -> d.getPushToken())
                .toList();
    }

    private boolean canReceive(Long userId, PrefType type) {
        NotificationPreferences prefs = prefsRepo.findByUserId(userId).orElse(null);
        if (prefs == null) return true; // domyślnie wszystko włączone
        if (!prefs.isPushEnabled()) return false;
        return switch (type) {
            case STATUS_UPDATES  -> prefs.isStatusUpdatesEnabled();
            case COMMENT_REPLIES -> prefs.isCommentRepliesEnabled();
            case CHAT_MESSAGES   -> prefs.isPushEnabled();
        };
    }

    // ─── Chat powiadomienia ────────────────────────────────────────────────────

    /** Powiadamia obywatela że admin odpowiedział na wątek. */
    @Async("chatNotificationExecutor")
    @Transactional(readOnly = true)
    public void notifyChatAdminReply(Long threadId, Long messageId, Long citizenId) {
        if (!canReceive(citizenId, PrefType.CHAT_MESSAGES)) return;
        List<String> tokens = tokensFor(citizenId);
        if (tokens.isEmpty()) return;

        chatMessageRepository.findByIdWithSender(messageId).ifPresent(msg -> {
            String senderName = displayName(msg.getSender());
            expoPushService.send(tokens,
                    senderName + " odpowiedział na Twój wątek",
                    truncate(msg.getBody(), 100),
                    Map.of("threadId", threadId, "type", "CHAT_ADMIN_REPLY")
            );
        });
    }

    /** Powiadamia urzędnika (jeśli przypisany) że obywatel wysłał nową wiadomość. */
    @Async("chatNotificationExecutor")
    @Transactional(readOnly = true)
    public void notifyChatUserMessage(Long threadId, Long messageId, Long senderId, Long assignedToId) {
        if (assignedToId == null) return;
        if (!canReceive(assignedToId, PrefType.CHAT_MESSAGES)) return;
        List<String> tokens = tokensFor(assignedToId);
        if (tokens.isEmpty()) return;

        chatMessageRepository.findByIdWithSender(messageId).ifPresent(msg -> {
            String senderName = displayName(msg.getSender());
            expoPushService.send(tokens,
                    senderName + " wysłał wiadomość w wątku",
                    truncate(msg.getBody(), 100),
                    Map.of("threadId", threadId, "type", "CHAT_USER_MESSAGE")
            );
        });
    }

    /** Powiadamia właściciela pulsa o nowym wątku czatu otwartym przez inną osobę. */
    @Async("chatNotificationExecutor")
    @Transactional(readOnly = true)
    public void notifyChatNewThread(Long threadId, Long recipientId) {
        if (!canReceive(recipientId, PrefType.CHAT_MESSAGES)) return;
        List<String> tokens = tokensFor(recipientId);
        if (tokens.isEmpty()) return;

        chatThreadRepository.findByIdWithDetails(threadId).ifPresent(thread -> {
            String subject = thread.getSubject() != null ? truncate(thread.getSubject(), 60) : "nowy wątek";
            expoPushService.send(tokens,
                    "Nowy wątek czatu",
                    subject,
                    Map.of("threadId", threadId, "type", "CHAT_NEW_THREAD")
            );
        });
    }

    /** Powiadamia obywatela o zmianie statusu wątku. */
    @Async("chatNotificationExecutor")
    @Transactional(readOnly = true)
    public void notifyChatStatusChange(Long threadId, ChatThreadStatus newStatus, Long citizenId) {
        if (!canReceive(citizenId, PrefType.CHAT_MESSAGES)) return;
        List<String> tokens = tokensFor(citizenId);
        if (tokens.isEmpty()) return;

        chatThreadRepository.findByIdWithDetails(threadId).ifPresent(thread -> {
            String subject = thread.getSubject() != null ? truncate(thread.getSubject(), 50) : "Twój wątek";
            expoPushService.send(tokens,
                    "Zmiana statusu wątku",
                    subject + " — " + newStatus.label(),
                    Map.of("threadId", threadId, "type", "CHAT_STATUS_CHANGE", "status", newStatus.name())
            );
        });
    }

    private enum PrefType { STATUS_UPDATES, COMMENT_REPLIES, CHAT_MESSAGES }

    private static String displayName(User user) {
        if (user == null) return "Ktoś";
        String name = user.getFirstName();
        return (name != null && !name.isBlank()) ? name : "Użytkownik";
    }

    private static String truncate(String s, int max) {
        if (s == null || s.isBlank()) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
