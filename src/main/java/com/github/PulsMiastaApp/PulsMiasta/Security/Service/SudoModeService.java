package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
public class SudoModeService {

    private static final String PREFIX = "sudo:";

    private final StringRedisTemplate redisTemplate;
    private final Duration sudoModeDuration;

    public SudoModeService(
            StringRedisTemplate redisTemplate,
            @Value("${auth.sudo.ttl-minutes:15}") long sudoTtlMinutes
    ) {
        this.redisTemplate = redisTemplate;
        this.sudoModeDuration = Duration.ofMinutes(sudoTtlMinutes);
    }

    public boolean isSudoModeActive(String sessionToken) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(PREFIX + sessionToken));
    }

    public void activateSudoMode(String sessionToken) {
        redisTemplate.opsForValue().set(PREFIX + sessionToken, "true", sudoModeDuration);
    }

    public void deactivateSudoMode(String sessionToken) {
        redisTemplate.delete(PREFIX + sessionToken);
    }

    /** Returns seconds remaining until sudo expires, or 0 if not active. */
    public long getRemainingTtlSeconds(String sessionToken) {
        Long ttl = redisTemplate.getExpire(PREFIX + sessionToken, TimeUnit.SECONDS);
        return (ttl != null && ttl > 0) ? ttl : 0L;
    }

    public long getSudoTtlSeconds() {
        return sudoModeDuration.getSeconds();
    }
}
