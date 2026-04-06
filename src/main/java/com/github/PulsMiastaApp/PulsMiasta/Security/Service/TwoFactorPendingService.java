package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Manages short-lived "2FA pending" tokens issued when a login attempt requires
 * a second factor before a full session can be granted.
 * <p>
 * Redis key layout:
 * <ul>
 *   <li>{@code 2fa_pending:{token}} → userId</li>
 *   <li>{@code 2fa_methods:{token}} → comma-separated list of available methods (e.g. "TOTP,EMAIL_OTP,PASSKEY")</li>
 * </ul>
 * <p>
 * Flow:
 * <ol>
 *   <li>Password check succeeds → {@link #createPendingToken(Long, List)} → client receives pendingToken + availableMethods.</li>
 *   <li>Client submits second factor + pendingToken to the appropriate endpoint.</li>
 *   <li>{@link #consumePendingToken(String)} → returns userId (use-once, auto-deleted).</li>
 *   <li>Server creates full session.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class TwoFactorPendingService {

    private static final String PREFIX = "2fa_pending:";
    private static final String METHODS_PREFIX = "2fa_methods:";

    private final StringRedisTemplate redisTemplate;

    @Value("${auth.totp.pending-ttl-minutes:5}")
    private long pendingTtlMinutes;

    /**
     * Creates a short-lived pending token for the given user with the available 2FA methods.
     *
     * @param userId           the user awaiting 2FA completion
     * @param availableMethods the 2FA methods the user can choose from (e.g. "TOTP", "EMAIL_OTP", "PASSKEY")
     * @return the opaque token to send to the client
     */
    public String createPendingToken(Long userId, List<String> availableMethods) {
        String token = UUID.randomUUID().toString();
        Duration ttl = Duration.ofMinutes(pendingTtlMinutes);

        redisTemplate.opsForValue().set(PREFIX + token, userId.toString(), ttl);
        redisTemplate.opsForValue().set(METHODS_PREFIX + token, String.join(",", availableMethods), ttl);

        return token;
    }

    /**
     * Validates the pending token and returns the associated userId WITHOUT deleting it.
     * Use this for intermediate steps (e.g. sending an OTP or beginning a passkey challenge)
     * that must not consume the token.
     *
     * @param token the pendingToken from the client
     * @return the userId
     * @throws ResponseStatusException 401 if the token is unknown or expired
     */
    public Long validatePendingToken(String token) {
        String userIdStr = redisTemplate.opsForValue().get(PREFIX + token);
        if (userIdStr == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired 2FA session");
        }
        return Long.valueOf(userIdStr);
    }

    /**
     * Returns the available 2FA methods for the given pending token.
     *
     * @param token the pendingToken from the client
     * @return the list of available methods
     * @throws ResponseStatusException 401 if the token is unknown or expired
     */
    public List<String> getAvailableMethods(String token) {
        String methods = redisTemplate.opsForValue().get(METHODS_PREFIX + token);
        if (methods == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired 2FA session");
        }
        return Arrays.asList(methods.split(","));
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
        String userIdStr = redisTemplate.opsForValue().get(PREFIX + token);
        if (userIdStr == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired 2FA session");
        }
        redisTemplate.delete(PREFIX + token);
        redisTemplate.delete(METHODS_PREFIX + token);
        return Long.valueOf(userIdStr);
    }
}
