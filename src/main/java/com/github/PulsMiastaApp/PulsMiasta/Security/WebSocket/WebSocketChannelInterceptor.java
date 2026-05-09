package com.github.PulsMiastaApp.PulsMiasta.Security.WebSocket;

import com.github.PulsMiastaApp.PulsMiasta.Repository.ChatThreadRepository;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Interceptor kanału STOMP.
 * <p>
 * Na CONNECT — wyciąga AuthPrincipal zapisany przez HandshakeInterceptor
 * i ustawia go jako principal wiadomości (Spring Security go potem widzi).
 * <p>
 * Na SUBSCRIBE — sprawdza czy użytkownik ma prawo subskrybować dany topic.
 * Format topicu: /topic/thread.{threadId}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketChannelInterceptor implements ChannelInterceptor {

    private final ChatThreadRepository chatThreadRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        StompCommand command = accessor.getCommand();

        if (StompCommand.CONNECT.equals(command)) {
            AuthPrincipal principal = extractPrincipal(accessor);
            if (principal == null) {
                throw new org.springframework.security.access.AccessDeniedException("Brak autoryzacji — wymagane zalogowanie");
            }
            accessor.setUser(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
            return message;
        }

        if (StompCommand.SUBSCRIBE.equals(command)) {
            AuthPrincipal principal = resolvedPrincipal(accessor);
            if (principal == null) {
                throw new org.springframework.security.access.AccessDeniedException("Brak autoryzacji");
            }
            String destination = accessor.getDestination();
            if (destination != null && destination.startsWith("/topic/thread.")) {
                long threadId = parseThreadId(destination);
                verifyThreadAccess(threadId, principal);
            }
        }

        return message;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private AuthPrincipal extractPrincipal(StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttrs = accessor.getSessionAttributes();
        if (sessionAttrs == null) return null;
        Object p = sessionAttrs.get("principal");
        return (p instanceof AuthPrincipal ap) ? ap : null;
    }

    private AuthPrincipal resolvedPrincipal(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof UsernamePasswordAuthenticationToken token
                && token.getPrincipal() instanceof AuthPrincipal ap) {
            return ap;
        }
        return extractPrincipal(accessor);
    }

    private static long parseThreadId(String destination) {
        try {
            String suffix = destination.substring("/topic/thread.".length());
            return Long.parseLong(suffix);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Nieprawidłowy topic: " + destination);
        }
    }

    /**
     * Weryfikuje czy principal ma dostęp do wątku:
     * - obywatel tylko do swoich wątków
     * - admin tylko do wątków w swoim obszarze
     */
    private void verifyThreadAccess(long threadId, AuthPrincipal principal) {
        chatThreadRepository.findByIdWithDetails(threadId).ifPresentOrElse(thread -> {
            if (principal.isAdmin()) {
                // SUPER_ADMIN bez ograniczeń
                if ("SUPER_ADMIN".equals(principal.role())) return;

                // Sprawdź scope admina na podstawie pulsa
                var pulse = thread.getPulse();
                String col = principal.adminScopeColumn();
                var vals = principal.adminScopeValues();
                if (col == null || vals == null || vals.isEmpty()) return;

                String pulseVal = switch (col) {
                    case "city"           -> pulse.getCity();
                    case "gmina_id"       -> pulse.getGminaId() != null ? pulse.getGminaId().toString() : null;
                    case "powiat_id"      -> pulse.getPowiatId() != null ? pulse.getPowiatId().toString() : null;
                    case "wojewodztwo_id" -> pulse.getWojewodztwoId() != null ? pulse.getWojewodztwoId().toString() : null;
                    default               -> null;
                };
                if (pulseVal == null || vals.stream().noneMatch(v -> v.equalsIgnoreCase(pulseVal))) {
                    throw new org.springframework.security.access.AccessDeniedException(
                            "Wątek nie jest w Twoim obszarze administracyjnym");
                }
            } else {
                // Obywatel — tylko swoje wątki
                if (!thread.getUser().getId().equals(principal.id())) {
                    throw new org.springframework.security.access.AccessDeniedException(
                            "Brak dostępu do tego wątku");
                }
            }
        }, () -> {
            throw new org.springframework.security.access.AccessDeniedException("Wątek nie istnieje");
        });
    }
}
