package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Short-lived single-use tokens for WebSocket authentication.
 * Redis key: ws_token:{token} → userId (TTL = 30s, deleted on first use).
 */
@Service
@RequiredArgsConstructor
public class WsTokenService {

    private static final String PREFIX = "ws_token:";
    private static final Duration TTL = Duration.ofSeconds(90);

    private final StringRedisTemplate redisTemplate;

    public String createToken(Long userId) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(PREFIX + token, userId.toString(), TTL);
        return token;
    }

    /** Returns userId and immediately deletes the token (single-use). */
    public Long consumeToken(String token) {
        String key = PREFIX + token;
        String value = redisTemplate.opsForValue().getAndDelete(key);
        if (value == null) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
