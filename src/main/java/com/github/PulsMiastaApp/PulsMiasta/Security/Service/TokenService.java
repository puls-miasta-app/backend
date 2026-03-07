package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ClientType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TokenService {

    private static final String SESSION_PREFIX = "session:";
    private static final String REMEMBER_PREFIX = "remember:";
    private static final String RENEW_SESSION_SCRIPT =
            "local userId = redis.call('GET', KEYS[1])\n" +
                    "if userId == false then\n" +
                    "  return nil\n" +
                    "end\n" +
                    "local sessionToken = ARGV[1]\n" +
                    "local sessionKey = ARGV[2]\n" +
                    "local ttl = tonumber(ARGV[3])\n" +
                    "redis.call('SET', sessionKey, userId, 'EX', ttl)\n" +
                    "return sessionToken";

    private final RedisTemplate<String, Long> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<String> renewSessionScript;
    private final Duration sessionTtl;
    private final Duration rememberMeWebTtl;
    private final Duration rememberMeMobileTtl;

    public TokenService(
            RedisTemplate<String, Long> redisTemplate,
            StringRedisTemplate stringRedisTemplate,
            @Value("${auth.session.ttl-minutes}") long sessionMinutes,
            @Value("${auth.remember-me.web.ttl-days}") long rememberMeWebDays,
            @Value("${auth.remember-me.mobile.ttl-days}") long rememberMeMobileDays
    ) {
        if (sessionMinutes <= 0) {
            throw new IllegalArgumentException("auth.session.ttl-minutes must be greater than 0");
        }
        if (rememberMeWebDays <= 0) {
            throw new IllegalArgumentException("auth.remember-me.web.ttl-days must be greater than 0");
        }
        if (rememberMeMobileDays <= 0) {
            throw new IllegalArgumentException("auth.remember-me.mobile.ttl-days must be greater than 0");
        }

        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.sessionTtl = Duration.ofMinutes(sessionMinutes);
        this.rememberMeWebTtl = Duration.ofDays(rememberMeWebDays);
        this.rememberMeMobileTtl = Duration.ofDays(rememberMeMobileDays);
        this.renewSessionScript = new DefaultRedisScript<>(RENEW_SESSION_SCRIPT, String.class);
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
     * <p>
     * Uses Redis Lua script for atomic execution to prevent duplicate session creation
     * in case of concurrent requests.
     *
     * @return new session token, or empty if the remember-me token is invalid/expired
     */
    public Optional<String> renewSessionFromRememberMe(String rememberMeToken) {
        String rememberKey = REMEMBER_PREFIX + rememberMeToken;
        String newSessionToken = UUID.randomUUID().toString();
        String sessionKey = SESSION_PREFIX + newSessionToken;
        long ttlSeconds = sessionTtl.getSeconds();

        List<String> keys = Collections.singletonList(rememberKey);

        String result = stringRedisTemplate.execute(renewSessionScript, keys, newSessionToken, sessionKey, String.valueOf(ttlSeconds));

        if (result == null) {
            return Optional.empty();
        }
        return Optional.of(result);
    }

    public void invalidateRememberMeToken(String token) {
        redisTemplate.delete(REMEMBER_PREFIX + token);
    }
}
