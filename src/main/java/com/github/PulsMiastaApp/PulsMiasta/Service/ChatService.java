package com.github.PulsMiastaApp.PulsMiasta.Service;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ChatDtos;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.ChatMessage;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.ChatThread;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Pulse;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ChatThreadStatus;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.UserRole;
import com.github.PulsMiastaApp.PulsMiasta.Push.PushNotificationService;
import com.github.PulsMiastaApp.PulsMiasta.Repository.ChatMessageRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.ChatThreadRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulseFeedJdbcRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.PulseRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ChatService {

    private static final int MAX_BODY_LENGTH = 4000;
    private static final int MAX_SUBJECT_LENGTH = 300;

    private final ChatThreadRepository threadRepository;
    private final ChatMessageRepository messageRepository;
    private final PulseRepository pulseRepository;
    private final PulseFeedJdbcRepository pulseFeedJdbcRepository;
    private final UserRepository userRepository;
    private final PushNotificationService pushNotificationService;
    private final SimpMessagingTemplate messagingTemplate;

    // ─── Otwieranie wątku ─────────────────────────────────────────────────────

    @Transactional
    public ChatDtos.ChatThreadResponse openThread(Long pulseId, Long userId, String subject, String firstMessage) {
        validateBody(firstMessage, MAX_BODY_LENGTH);
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Temat wątku jest wymagany");
        }
        if (subject.length() > MAX_SUBJECT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Temat jest za długi (maks. " + MAX_SUBJECT_LENGTH + " znaków)");
        }

        if (threadRepository.existsByPulseIdAndUserId(pulseId, userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wątek dla tego zgłoszenia już istnieje");
        }

        Pulse pulse = pulseFeedJdbcRepository.findByIdWithPhotos(pulseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Zgłoszenie nie znalezione"));
        User user = requireUser(userId);

        ChatThread thread = new ChatThread();
        thread.setPulse(pulseRepository.getReferenceById(pulseId));
        thread.setUser(user);
        thread.setSubject(subject.trim());
        threadRepository.save(thread);

        ChatMessage msg = buildMessage(thread, user, firstMessage);
        messageRepository.save(msg);

        thread.setMessagesCount(1);
        thread.setLastMessageAt(msg.getCreatedAt());
        threadRepository.save(thread);

        Long threadId = thread.getId();
        Long pulseOwnerId = pulse.getUser() != null ? pulse.getUser().getId() : null;
        if (pulseOwnerId != null && !pulseOwnerId.equals(userId)) {
            registerAfterCommit(() -> pushNotificationService.notifyChatNewThread(threadId, pulseOwnerId));
        }

        return toThreadResponse(thread);
    }

    // ─── Wiadomości — odczyt ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ChatDtos.ChatThreadWithMessagesResponse getThread(Long threadId, AuthPrincipal principal) {
        ChatThread thread = requireThread(threadId);
        requireAccess(thread, principal);

        int page = 0;
        int size = 50;
        Page<ChatMessage> msgPage = messageRepository.findAllByThreadIdOrderByCreatedAtAsc(
                threadId, PageRequest.of(page, size));

        List<ChatDtos.ChatMessageResponse> messages = msgPage.getContent().stream()
                .map(this::toMessageResponse)
                .toList();

        return new ChatDtos.ChatThreadWithMessagesResponse(
                toThreadResponse(thread),
                messages,
                page,
                msgPage.getTotalPages(),
                msgPage.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public Page<ChatDtos.ChatMessageResponse> getMessages(Long threadId, AuthPrincipal principal, int page, int size) {
        ChatThread thread = requireThread(threadId);
        requireAccess(thread, principal);
        return messageRepository.findAllByThreadIdOrderByCreatedAtAsc(threadId, PageRequest.of(page, size))
                .map(this::toMessageResponse);
    }

    // ─── Lista wątków — użytkownik ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<ChatDtos.ChatThreadResponse> listMyThreads(Long userId, int page, int size) {
        return threadRepository.findAllByUserIdOrderByUpdatedAtDesc(userId, PageRequest.of(page, size))
                .map(this::toThreadResponse);
    }

    // ─── Lista wątków — admin ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<ChatDtos.ChatThreadResponse> listForAdmin(AuthPrincipal principal, String rawStatus, int page, int size) {
        ChatThreadStatus status = rawStatus != null ? parseStatus(rawStatus) : null;
        PageRequest pageable = PageRequest.of(page, size);

        if (principal.userRole() == UserRole.SUPER_ADMIN) {
            if (status != null) {
                return threadRepository.findAllByStatusOrderByUpdatedAtDesc(status, pageable)
                        .map(this::toThreadResponse);
            }
            return threadRepository.findAll(PageRequest.of(page, size, Sort.by("updatedAt").descending()))
                    .map(this::toThreadResponse);
        }

        return switch (principal.userRole()) {
            case ADMIN_MIASTA -> {
                Set<String> cities = principal.managedMiasta();
                if (cities.isEmpty()) yield Page.empty(pageable);
                yield threadRepository.findAllByCityIn(cities, pageable).map(this::toThreadResponse);
            }
            case ADMIN_GMINY -> {
                Set<Long> ids = principal.managedGminyIds();
                if (ids.isEmpty()) yield Page.empty(pageable);
                yield threadRepository.findAllByGminaIdIn(ids, pageable).map(this::toThreadResponse);
            }
            case ADMIN_POWIATU -> {
                Set<Long> ids = principal.managedPowiatyIds();
                if (ids.isEmpty()) yield Page.empty(pageable);
                yield threadRepository.findAllByPowiatIdIn(ids, pageable).map(this::toThreadResponse);
            }
            case ADMIN_WOJEWODZTWA -> {
                Set<Long> ids = principal.managedWojewodztwaIds();
                if (ids.isEmpty()) yield Page.empty(pageable);
                yield threadRepository.findAllByWojewodztwoIdIn(ids, pageable).map(this::toThreadResponse);
            }
            default -> Page.empty(pageable);
        };
    }

    // ─── Wysyłanie wiadomości ─────────────────────────────────────────────────

    @Transactional
    public ChatDtos.ChatMessageResponse sendMessage(Long threadId, AuthPrincipal principal, String body) {
        validateBody(body, MAX_BODY_LENGTH);
        ChatThread thread = requireThread(threadId);
        requireAccess(thread, principal);

        if (thread.getStatus() == ChatThreadStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wątek jest zamknięty. Nie można wysyłać wiadomości.");
        }

        User sender = requireUser(principal.id());
        ChatMessage msg = buildMessage(thread, sender, body);
        messageRepository.save(msg);

        thread.setMessagesCount(thread.getMessagesCount() + 1);
        thread.setLastMessageAt(msg.getCreatedAt());
        if (thread.getStatus() == ChatThreadStatus.NEEDS_INFO && !principal.isAdmin()) {
            thread.setStatus(ChatThreadStatus.IN_PROGRESS);
        }
        threadRepository.save(thread);

        Long msgId = msg.getId();
        Long senderId = principal.id();
        Long threadIdCaptured = threadId;
        ChatDtos.ChatMessageResponse msgResponse = toMessageResponse(msg);

        // Broadcast real-time przez WebSocket + push notification — po commicie transakcji
        if (principal.isAdmin()) {
            Long citizenId = thread.getUser().getId();
            registerAfterCommit(() -> {
                broadcastMessage(threadIdCaptured, msgResponse);
                pushNotificationService.notifyChatAdminReply(threadIdCaptured, msgId, citizenId);
            });
        } else {
            Long assignedId = thread.getAssignedTo() != null ? thread.getAssignedTo().getId() : null;
            registerAfterCommit(() -> {
                broadcastMessage(threadIdCaptured, msgResponse);
                pushNotificationService.notifyChatUserMessage(threadIdCaptured, msgId, senderId, assignedId);
            });
        }

        return msgResponse;
    }

    // ─── Zmiana statusu — admin ───────────────────────────────────────────────

    @Transactional
    public ChatDtos.ChatThreadResponse updateStatus(Long threadId, AuthPrincipal principal, String rawStatus) {
        ChatThread thread = requireThread(threadId);
        requireAdminAccess(thread, principal);

        ChatThreadStatus newStatus = parseStatus(rawStatus);
        thread.setStatus(newStatus);
        if (newStatus == ChatThreadStatus.CLOSED) {
            thread.setClosedAt(LocalDateTime.now());
        }
        threadRepository.save(thread);

        Long citizenId = thread.getUser().getId();
        Long threadIdCaptured = threadId;
        registerAfterCommit(() -> pushNotificationService.notifyChatStatusChange(threadIdCaptured, newStatus, citizenId));

        return toThreadResponse(thread);
    }

    // ─── Przypisanie urzędnika ────────────────────────────────────────────────

    @Transactional
    public ChatDtos.ChatThreadResponse assignThread(Long threadId, AuthPrincipal principal, Long assignedToId) {
        ChatThread thread = requireThread(threadId);
        requireAdminAccess(thread, principal);

        if (assignedToId != null) {
            User official = requireUser(assignedToId);
            thread.setAssignedTo(official);
            if (thread.getStatus() == ChatThreadStatus.OPEN) {
                thread.setStatus(ChatThreadStatus.IN_PROGRESS);
            }
        } else {
            thread.setAssignedTo(null);
        }
        threadRepository.save(thread);
        return toThreadResponse(thread);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private ChatThread requireThread(Long threadId) {
        return threadRepository.findByIdWithDetails(threadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wątek nie znaleziony"));
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Użytkownik nie znaleziony"));
    }

    private void requireAccess(ChatThread thread, AuthPrincipal principal) {
        if (principal.isAdmin()) {
            requireAdminAccess(thread, principal);
        } else {
            if (!thread.getUser().getId().equals(principal.id())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Brak dostępu do tego wątku");
            }
        }
    }

    private void requireAdminAccess(ChatThread thread, AuthPrincipal principal) {
        if (!principal.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tylko administrator może wykonać tę operację");
        }
        if (principal.userRole() == UserRole.SUPER_ADMIN) return;

        Pulse pulse = thread.getPulse();
        String col = principal.adminScopeColumn();
        Set<String> vals = principal.adminScopeValues();
        if (col == null || vals == null || vals.isEmpty()) return;

        String pulseVal = switch (col) {
            case "city"           -> pulse.getCity();
            case "gmina_id"       -> pulse.getGminaId() != null ? pulse.getGminaId().toString() : null;
            case "powiat_id"      -> pulse.getPowiatId() != null ? pulse.getPowiatId().toString() : null;
            case "wojewodztwo_id" -> pulse.getWojewodztwoId() != null ? pulse.getWojewodztwoId().toString() : null;
            default               -> null;
        };
        if (pulseVal == null || vals.stream().noneMatch(v -> v.equalsIgnoreCase(pulseVal))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Wątek nie jest w Twoim obszarze administracyjnym");
        }
    }

    private static ChatMessage buildMessage(ChatThread thread, User sender, String body) {
        ChatMessage msg = new ChatMessage();
        msg.setThread(thread);
        msg.setSender(sender);
        msg.setSenderRole(sender.getRole());
        msg.setBody(body.trim());
        return msg;
    }

    private static String validateBody(String body, int maxLen) {
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Treść wiadomości jest wymagana");
        }
        if (body.trim().length() > maxLen) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Wiadomość jest za długa (maks. " + maxLen + " znaków)");
        }
        return body.trim();
    }

    private static ChatThreadStatus parseStatus(String raw) {
        try {
            return ChatThreadStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidłowy status: " + raw);
        }
    }

    /** Wysyła wiadomość do wszystkich subskrybentów wątku przez WebSocket. */
    private void broadcastMessage(Long threadId, ChatDtos.ChatMessageResponse msg) {
        messagingTemplate.convertAndSend("/topic/thread." + threadId, msg);
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

    // ─── Mapowanie ────────────────────────────────────────────────────────────

    public ChatDtos.ChatThreadResponse toThreadResponse(ChatThread t) {
        User u = t.getUser();
        User assigned = t.getAssignedTo();
        Pulse pulse = t.getPulse();
        return new ChatDtos.ChatThreadResponse(
                String.valueOf(t.getId()),
                pulse != null ? String.valueOf(pulse.getId()) : null,
                pulse != null ? pulse.getTitle() : null,
                u != null ? String.valueOf(u.getId()) : null,
                u != null ? u.getFirstName() : null,
                u != null ? u.getLastName() : null,
                u != null ? u.getEmail() : null,
                assigned != null ? String.valueOf(assigned.getId()) : null,
                assigned != null ? assigned.getFirstName() : null,
                assigned != null ? assigned.getLastName() : null,
                t.getStatus().name(),
                t.getStatus().label(),
                t.getSubject(),
                t.getMessagesCount(),
                t.getLastMessageAt() != null ? t.getLastMessageAt().toString() : null,
                t.getCreatedAt() != null ? t.getCreatedAt().toString() : null,
                t.getUpdatedAt() != null ? t.getUpdatedAt().toString() : null
        );
    }

    public ChatDtos.ChatMessageResponse toMessageResponse(ChatMessage m) {
        User s = m.getSender();
        return new ChatDtos.ChatMessageResponse(
                String.valueOf(m.getId()),
                String.valueOf(m.getThread().getId()),
                s != null ? String.valueOf(s.getId()) : null,
                s != null ? s.getFirstName() : null,
                s != null ? s.getLastName() : null,
                s != null ? s.getEmail() : null,
                m.getSenderRole(),
                m.getBody(),
                m.getCreatedAt() != null ? m.getCreatedAt().toString() : null,
                m.getEditedAt() != null ? m.getEditedAt().toString() : null
        );
    }
}
