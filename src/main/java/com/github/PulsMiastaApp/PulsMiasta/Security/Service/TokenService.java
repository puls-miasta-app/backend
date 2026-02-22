package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ClientType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
public class TokenService {

    private static final String SESSION_PREFIX = "session:";
    private static final String REMEMBER_PREFIX = "remember:";

    private final RedisTemplate<String, Long> redisTemplate;
    private final Duration sessionTtl;
    private final Duration rememberMeWebTtl;
    private final Duration rememberMeMobileTtl;

    public TokenService(
            RedisTemplate<String, Long> redisTemplate,
            @Value("${auth.session.ttl-minutes}") long sessionMinutes,
            @Value("${auth.remember-me.web.ttl-days}") long rememberMeWebDays,
            @Value("${auth.remember-me.mobile.ttl-days}") long rememberMeMobileDays
    ) {
        this.redisTemplate = redisTemplate;
        this.sessionTtl = Duration.ofMinutes(sessionMinutes);
        this.rememberMeWebTtl = Duration.ofDays(rememberMeWebDays);
        this.rememberMeMobileTtl = Duration.ofDays(rememberMeMobileDays);
    }

    // -------------------------------------------------------------------------
    // Session (sliding window — TTL reset on every authenticated request)
    // -------------------------------------------------------------------------

    public String createSession(Long userId) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(SESSION_PREFIX + token, userId, sessionTtl);
        return token;
    }

    /**
     * Returns the userId associated with the session token and resets its TTL
     * (sliding window). Returns empty if the token does not exist or has expired.
     */
    public Optional<Long> getUserIdAndSlide(String token) {
        String key = SESSION_PREFIX + token;
        Long userId = redisTemplate.opsForValue().get(key);
        if (userId != null) {
            redisTemplate.expire(key, sessionTtl);
        }
        return Optional.ofNullable(userId);
    }

    public void invalidateSession(String token) {
        redisTemplate.delete(SESSION_PREFIX + token);
    }

    // -------------------------------------------------------------------------
    // Remember-me (fixed TTL — not sliding; used to restore a new session)
    // -------------------------------------------------------------------------

    public String createRememberMeToken(Long userId, ClientType clientType) {
        String token = UUID.randomUUID().toString();
        Duration ttl = clientType == ClientType.MOBILE ? rememberMeMobileTtl : rememberMeWebTtl;
        redisTemplate.opsForValue().set(REMEMBER_PREFIX + token, userId, ttl);
        return token;
    }

    /**
     * Validates a remember-me token and, if valid, creates a fresh session.
     * The remember-me token itself is NOT consumed — it stays valid until its own TTL expires,
     * allowing the user to stay logged in across multiple session expirations.
     *
     * @return new session token, or empty if the remember-me token is invalid/expired
     */
    public Optional<String> renewSessionFromRememberMe(String rememberMeToken) {
        Long userId = redisTemplate.opsForValue().get(REMEMBER_PREFIX + rememberMeToken);
        if (userId == null) {
            return Optional.empty();
        }
        return Optional.of(createSession(userId));
    }

    public void invalidateRememberMeToken(String token) {
        redisTemplate.delete(REMEMBER_PREFIX + token);
    }
}
