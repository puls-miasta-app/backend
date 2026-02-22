package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SudoModeService {

    private static final String PREFIX = "sudo:";
    private static final int SUDO_MODE_DURATION_MINUTES = 15;

    private final StringRedisTemplate redisTemplate;

    public boolean isSudoModeActive(String sessionToken) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(PREFIX + sessionToken));
    }

    public void activateSudoMode(String sessionToken) {
        Duration ttl = Duration.ofMinutes(SUDO_MODE_DURATION_MINUTES);
        redisTemplate.opsForValue().set(PREFIX + sessionToken, "true", ttl);
    }

    public void deactivateSudoMode(String sessionToken) {
        redisTemplate.delete(PREFIX + sessionToken);
    }
}
