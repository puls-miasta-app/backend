package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.UUID;

/**
 * Manages short-lived "2FA pending" tokens issued when a login attempt requires
 * a second factor (TOTP) before a full session can be granted.
 * <p>
 * Flow:
 * <ol>
 *   <li>Password check succeeds → {@link #createPendingToken(Long)} → client receives pendingToken.</li>
 *   <li>Client submits TOTP code + pendingToken to {@code POST /auth/login/totp}.</li>
 *   <li>{@link #consumePendingToken(String)} → returns userId (use-once, auto-deleted).</li>
 *   <li>Server creates full session.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class TwoFactorPendingService {

    private static final String PREFIX = "2fa_pending:";

    private final RedisTemplate<String, Long> redisTemplate;

    @Value("${auth.totp.pending-ttl-minutes:5}")
    private long pendingTtlMinutes;

    /**
     * Creates a short-lived pending token for the given user.
     *
     * @param userId the user awaiting 2FA completion
     * @return the opaque token to send to the client
     */
    public String createPendingToken(Long userId) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(PREFIX + token, userId, Duration.ofMinutes(pendingTtlMinutes));
        return token;
    }

    /**
     * Consumes the pending token and returns the associated userId.
     * The token is deleted immediately (use-once, replay-proof).
     *
     * @param token the pendingToken from the client
     * @return the userId
     * @throws ResponseStatusException 401 if the token is unknown or expired
     */
    public Long consumePendingToken(String token) {
        String key = PREFIX + token;
        Long userId = redisTemplate.opsForValue().get(key);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired 2FA session");
        }
        redisTemplate.delete(key);
        return userId;
    }
}
