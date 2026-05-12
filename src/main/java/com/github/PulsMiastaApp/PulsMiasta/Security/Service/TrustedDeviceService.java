package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Manages trusted-device tokens so users can skip 2FA on recognized devices.
 * <p>
 * Redis key: {@code trusted_device:{userId}:{token}} → {@code "1"} (TTL = {@code auth.trusted-device.ttl-days}).
 * <p>
 * Flow:<ol>
 *   <li>User completes 2FA with {@code rememberDevice=true} → {@link #createToken} → cookie set on response.</li>
 *   <li>Next login: step-1 reads the cookie → {@link #isTokenValid} → skip 2FA and grant session directly.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class TrustedDeviceService {

    private static final String PREFIX = "trusted_device:";

    private final StringRedisTemplate redisTemplate;

    @Value("${auth.trusted-device.ttl-days:30}")
    private long ttlDays;

    /**
     * Creates a new trusted-device token for the given user.
     *
     * @return the opaque token to store in the client's cookie
     */
    public String createToken(Long userId) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(key(userId, token), "1", Duration.ofDays(ttlDays));
        return token;
    }

    /**
     * Returns {@code true} when the token exists in Redis and belongs to the given user.
     */
    public boolean isTokenValid(Long userId, String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(userId, token)));
    }

    /**
     * Revokes a single trusted-device token (e.g. on logout from all devices).
     */
    public void revokeToken(Long userId, String token) {
        redisTemplate.delete(key(userId, token));
    }

    /**
     * Returns the TTL in seconds — used to set the cookie's Max-Age.
     */
    public long getTtlSeconds() {
        return ttlDays * 24 * 60 * 60;
    }

    private String key(Long userId, String token) {
        return PREFIX + userId + ":" + token;
    }
}
