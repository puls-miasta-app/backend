package com.github.PulsMiastaApp.PulsMiasta.Service;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ChatDtos;
import com.github.PulsMiastaApp.PulsMiasta.Crypto.CryptoService;
import com.github.PulsMiastaApp.PulsMiasta.Crypto.EncryptedData;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.ChatMessage;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.ChatThread;
import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.ChatThreadStatus;
import com.github.PulsMiastaApp.PulsMiasta.Model.Enums.UserRole;
import com.github.PulsMiastaApp.PulsMiasta.Push.PushNotificationService;
import com.github.PulsMiastaApp.PulsMiasta.Repository.ChatMessageRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.ChatThreadRepository;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
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

import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatService {

    private static final int MAX_BODY_LENGTH = 4000;
    private static final int MAX_SUBJECT_LENGTH = 300;

    private final ChatThreadRepository threadRepository;
    private final ChatMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final PushNotificationService pushNotificationService;
    private final SimpMessagingTemplate messagingTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;
    private final CryptoService cryptoService;

    // ─── Otwieranie wątku ─────────────────────────────────────────────────────

    @Transactional
    public ChatDtos.ChatThreadResponse openThread(Long pulseId, Long userId, String subject, String firstMessage) {
        String trimmedMessage = validateBody(firstMessage, MAX_BODY_LENGTH);
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Temat wątku jest wymagany");
        }
        if (subject.length() > MAX_SUBJECT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Temat jest za długi (maks. " + MAX_SUBJECT_LENGTH + " znaków)");
        }

        if (threadRepository.existsByPulseIdAndUserId(pulseId, userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wątek dla tego zgłoszenia już istnieje");
        }

        // JDBC zamiast JPA — obejście bugu Hibernate 7 + MySQL Connector/J na tabeli pulses
        PulseInfo pulseInfo = queryPulseInfo(pulseId);
        if (pulseInfo == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Zgłoszenie nie znalezione");
        }
        User user = requireUser(userId);

        LocalDateTime now = LocalDateTime.now();
        ChatThread thread = new ChatThread();
        // getReference nie wykonuje SELECT — tylko proxy z ID (bezpieczne dla FK)
        thread.setPulse(entityManager.getReference(com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.Pulse.class, pulseId));
        thread.setUser(user);
        thread.setSubject(subject.trim());
        thread.setMessagesCount(1);
        thread.setLastMessageAt(now);

        try {
            threadRepository.save(thread);
            ChatMessage msg = buildMessage(thread, user, trimmedMessage);
            messageRepository.save(msg);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wątek dla tego zgłoszenia już istnieje");
        }

        Long threadId = thread.getId();
        Long pulseOwnerId = pulseInfo.ownerId();
        if (pulseOwnerId != null && !pulseOwnerId.equals(userId)) {
            registerAfterCommit(() -> pushNotificationService.notifyChatNewThread(threadId, pulseOwnerId));
        }

        return toThreadResponse(thread, pulseInfo);
    }

    // ─── Wiadomości — odczyt ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ChatDtos.ChatThreadWithMessagesResponse getThread(Long threadId, AuthPrincipal principal, int page, int size) {
        ChatThread thread = requireThread(threadId);
        requireAccess(thread, principal);

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
        Page<ChatThread> threads = threadRepository.findAllByUserIdOrderByUpdatedAtDesc(userId, PageRequest.of(page, size));
        Map<Long, PulseInfo> pulseInfoMap = batchQueryPulseInfo(threads.getContent().stream()
                .map(ChatThread::getPulseId).collect(Collectors.toList()));
        return threads.map(t -> toThreadResponse(t, pulseInfoMap.get(t.getPulseId())));
    }

    // ─── Lista wątków — admin ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<ChatDtos.ChatThreadResponse> listForAdmin(AuthPrincipal principal, String rawStatus, int page, int size) {
        ChatThreadStatus status = rawStatus != null ? parseStatus(rawStatus) : null;
        String statusName = status != null ? status.name() : null;
        PageRequest pageable = PageRequest.of(page, size);

        Page<ChatThread> threads;
        if (principal.userRole() == UserRole.SUPER_ADMIN) {
            if (status != null) {
                threads = threadRepository.findAllByStatusOrderByUpdatedAtDesc(status, pageable);
            } else {
                threads = threadRepository.findAll(PageRequest.of(page, size, Sort.by("updatedAt").descending()));
            }
        } else {
            threads = switch (principal.userRole()) {
                case ADMIN_MIASTA -> {
                    Set<String> cities = principal.managedMiasta();
                    if (cities.isEmpty()) yield Page.empty(pageable);
                    yield threadRepository.findAllByCityIn(cities, statusName, pageable);
                }
                case ADMIN_GMINY -> {
                    Set<Long> ids = principal.managedGminyIds();
                    if (ids.isEmpty()) yield Page.empty(pageable);
                    yield threadRepository.findAllByGminaIdIn(ids, statusName, pageable);
                }
                case ADMIN_POWIATU -> {
                    Set<Long> ids = principal.managedPowiatyIds();
                    if (ids.isEmpty()) yield Page.empty(pageable);
                    yield threadRepository.findAllByPowiatIdIn(ids, statusName, pageable);
                }
                case ADMIN_WOJEWODZTWA -> {
                    Set<Long> ids = principal.managedWojewodztwaIds();
                    if (ids.isEmpty()) yield Page.empty(pageable);
                    yield threadRepository.findAllByWojewodztwoIdIn(ids, statusName, pageable);
                }
                default -> Page.empty(pageable);
            };
        }

        Map<Long, PulseInfo> pulseInfoMap = batchQueryPulseInfo(threads.getContent().stream()
                .map(ChatThread::getPulseId).collect(Collectors.toList()));
        return threads.map(t -> toThreadResponse(t, pulseInfoMap.get(t.getPulseId())));
    }

    // ─── Wysyłanie wiadomości ─────────────────────────────────────────────────

    @Transactional
    public ChatDtos.ChatMessageResponse sendMessage(Long threadId, AuthPrincipal principal, String body) {
        String trimmedBody = validateBody(body, MAX_BODY_LENGTH);
        ChatThread thread = requireThread(threadId);
        requireAccess(thread, principal);

        if (thread.getStatus() == ChatThreadStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wątek jest zamknięty. Nie można wysyłać wiadomości.");
        }

        User sender = requireUser(principal.id());
        ChatMessage msg = buildMessage(thread, sender, trimmedBody);
        messageRepository.save(msg);

        thread.setMessagesCount(thread.getMessagesCount() + 1);
        thread.setLastMessageAt(msg.getCreatedAt());
        if (thread.getStatus() == ChatThreadStatus.NEEDS_INFO && !principal.isAdmin()) {
            thread.setStatus(ChatThreadStatus.IN_PROGRESS);
        }
        threadRepository.save(thread);

        Long threadIdCaptured = threadId;
        ChatDtos.ChatMessageResponse msgResponse = toMessageResponse(msg);
        String senderName = sender.getFirstName() != null && !sender.getFirstName().isBlank()
                ? sender.getFirstName() : "Użytkownik";
        String plainBody = msgResponse.body();

        // Broadcast real-time przez WebSocket + push notification — po commicie transakcji
        if (principal.isAdmin()) {
            Long citizenId = thread.getUser().getId();
            registerAfterCommit(() -> {
                broadcastMessage(threadIdCaptured, msgResponse);
                if (!citizenId.equals(principal.id())) {
                    pushNotificationService.notifyChatAdminReply(threadIdCaptured, citizenId, senderName, plainBody);
                }
            });
        } else {
            Long assignedId = thread.getAssignedTo() != null ? thread.getAssignedTo().getId() : null;
            registerAfterCommit(() -> {
                broadcastMessage(threadIdCaptured, msgResponse);
                if (assignedId != null && !assignedId.equals(principal.id())) {
                    pushNotificationService.notifyChatUserMessage(threadIdCaptured, assignedId, senderName, plainBody);
                }
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

        String col = principal.adminScopeColumn();
        Set<String> vals = principal.adminScopeValues();
        if (col == null || vals == null || vals.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Brak przypisanego obszaru administracyjnego");
        }

        // JDBC zamiast thread.getPulse() — unikamy lazy-load i bugu Hibernate/MySQL
        Long pulseId = thread.getPulseId();
        PulseInfo info = pulseId != null ? queryPulseInfo(pulseId) : null;
        if (info == null) return;

        String pulseVal = switch (col) {
            case "city"           -> info.city();
            case "gmina_id"       -> info.gminaId() != null ? info.gminaId().toString() : null;
            case "powiat_id"      -> info.powiatId() != null ? info.powiatId().toString() : null;
            case "wojewodztwo_id" -> info.wojewodztwoId() != null ? info.wojewodztwoId().toString() : null;
            default               -> null;
        };
        if (pulseVal == null || vals.stream().noneMatch(v -> v.equalsIgnoreCase(pulseVal))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Wątek nie jest w Twoim obszarze administracyjnym");
        }
    }

    private ChatMessage buildMessage(ChatThread thread, User sender, String body) {
        ChatMessage msg = new ChatMessage();
        msg.setThread(thread);
        msg.setSender(sender);
        msg.setSenderRole(sender.getRole());
        msg.setBody(encryptBody(body));
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

    // ─── JDBC helpers (obejście bugu Hibernate 7 + MySQL Connector/J na pulses) ──

    private record PulseInfo(Long id, String title, Long ownerId, String city,
                             Long gminaId, Long powiatId, Long wojewodztwoId) {}

    private PulseInfo queryPulseInfo(Long pulseId) {
        var rows = jdbcTemplate.query(
                "SELECT id, title, user_id, city, gmina_id, powiat_id, wojewodztwo_id FROM pulses WHERE id = ?",
                (rs, rowNum) -> new PulseInfo(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getLong("user_id"),
                        rs.getString("city"),
                        rs.getObject("gmina_id", Long.class),
                        rs.getObject("powiat_id", Long.class),
                        rs.getObject("wojewodztwo_id", Long.class)
                ),
                pulseId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<Long, PulseInfo> batchQueryPulseInfo(Collection<Long> pulseIds) {
        List<Long> ids = pulseIds.stream().filter(id -> id != null).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) return Collections.emptyMap();
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        List<PulseInfo> rows = jdbcTemplate.query(
                "SELECT id, title, user_id, city, gmina_id, powiat_id, wojewodztwo_id FROM pulses WHERE id IN (" + placeholders + ")",
                (rs, rowNum) -> new PulseInfo(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getLong("user_id"),
                        rs.getString("city"),
                        rs.getObject("gmina_id", Long.class),
                        rs.getObject("powiat_id", Long.class),
                        rs.getObject("wojewodztwo_id", Long.class)
                ),
                ids.toArray());
        Map<Long, PulseInfo> map = new HashMap<>();
        for (PulseInfo info : rows) map.put(info.id(), info);
        return map;
    }

    // ─── Szyfrowanie treści wiadomości (AES-GCM, envelope encryption) ────────────

    private static final String ENC_PREFIX = "ENC:";

    private String encryptBody(String plaintext) {
        try {
            EncryptedData enc = cryptoService.encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
            return ENC_PREFIX + serializeEncryptedData(enc);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Błąd szyfrowania wiadomości: " + e.getMessage());
        }
    }

    private String decryptBody(String stored) {
        if (stored == null) return null;
        if (!stored.startsWith(ENC_PREFIX)) return stored; // backward-compat dla niezaszyfrowanych
        try {
            EncryptedData enc = deserializeEncryptedData(stored.substring(ENC_PREFIX.length()));
            return new String(cryptoService.decrypt(enc), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Błąd deszyfrowania wiadomości: " + e.getMessage());
        }
    }

    /**
     * Format binarny (→ Base64 URL-safe bez paddingu):
     *   [1B kekNameLen][kekName bytes]
     *   [12B dekIv]
     *   [2B encDekLen big-endian][encDek bytes]
     *   [12B iv]
     *   [ciphertext]
     */
    private static String serializeEncryptedData(EncryptedData d) {
        byte[] kekBytes = d.kekName().getBytes(StandardCharsets.UTF_8);
        int total = 1 + kekBytes.length + 12 + 2 + d.encryptedDek().length + 12 + d.ciphertext().length;
        byte[] buf = new byte[total];
        int pos = 0;
        buf[pos++] = (byte) kekBytes.length;
        System.arraycopy(kekBytes, 0, buf, pos, kekBytes.length); pos += kekBytes.length;
        System.arraycopy(d.dekIv(), 0, buf, pos, 12);             pos += 12;
        buf[pos++] = (byte) (d.encryptedDek().length >> 8);
        buf[pos++] = (byte)  d.encryptedDek().length;
        System.arraycopy(d.encryptedDek(), 0, buf, pos, d.encryptedDek().length); pos += d.encryptedDek().length;
        System.arraycopy(d.iv(), 0, buf, pos, 12);                pos += 12;
        System.arraycopy(d.ciphertext(), 0, buf, pos, d.ciphertext().length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static EncryptedData deserializeEncryptedData(String b64) {
        byte[] buf = Base64.getUrlDecoder().decode(b64);
        int pos = 0;
        int kekLen = buf[pos++] & 0xFF;
        String kekName = new String(buf, pos, kekLen, StandardCharsets.UTF_8); pos += kekLen;
        byte[] dekIv = Arrays.copyOfRange(buf, pos, pos + 12);                 pos += 12;
        int encDekLen = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);     pos += 2;
        byte[] encDek = Arrays.copyOfRange(buf, pos, pos + encDekLen);         pos += encDekLen;
        byte[] iv     = Arrays.copyOfRange(buf, pos, pos + 12);                pos += 12;
        byte[] ct     = Arrays.copyOfRange(buf, pos, buf.length);
        return new EncryptedData(ct, encDek, dekIv, iv, kekName);
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

    /** Wersja z pulseInfo z JDBC — używana gdy nie chcemy lazy-loadować Pulse. */
    public ChatDtos.ChatThreadResponse toThreadResponse(ChatThread t, PulseInfo pulseInfo) {
        User u = t.getUser();
        User assigned = t.getAssignedTo();
        return new ChatDtos.ChatThreadResponse(
                String.valueOf(t.getId()),
                pulseInfo != null ? String.valueOf(pulseInfo.id()) : t.getPulseId() != null ? String.valueOf(t.getPulseId()) : null,
                pulseInfo != null ? pulseInfo.title() : null,
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

    /** Wersja bez pulseInfo — fetchuje dane z JDBC automatycznie. */
    public ChatDtos.ChatThreadResponse toThreadResponse(ChatThread t) {
        PulseInfo info = t.getPulseId() != null ? queryPulseInfo(t.getPulseId()) : null;
        return toThreadResponse(t, info);
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
                decryptBody(m.getBody()),
                m.getCreatedAt() != null ? m.getCreatedAt().toString() : null,
                m.getEditedAt() != null ? m.getEditedAt().toString() : null
        );
    }
}
