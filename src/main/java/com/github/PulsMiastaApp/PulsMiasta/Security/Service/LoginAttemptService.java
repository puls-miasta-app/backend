package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class LoginAttemptService {

    private static final String ATTEMPTS_PREFIX = "login_attempts:";
    private static final String LOCKOUT_PREFIX = "account_lockout:";

    /**
     * Atomically increments the counter and sets TTL only on the first increment.
     * Prevents the race condition where the key could be left without an expiry
     * if the process crashes between INCR and EXPIRE.
     */
    private static final RedisScript<Long> INCR_WITH_EXPIRE = RedisScript.of(
            "local count = redis.call('INCR', KEYS[1])\n" +
                    "if count == 1 then\n" +
                    "  redis.call('EXPIRE', KEYS[1], ARGV[1])\n" +
                    "end\n" +
                    "return count",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final int maxAttempts;
    private final Duration lockoutDuration;
    private final Duration attemptsTtl;

    public LoginAttemptService(
            StringRedisTemplate redisTemplate,
            @Value("${auth.login.max-attempts:5}") int maxAttempts,
            @Value("${auth.login.lockout-minutes:30}") long lockoutMinutes,
            @Value("${auth.login.attempts-ttl-minutes:15}") long attemptsTtlMinutes) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("auth.login.max-attempts must be greater than 0");
        }
        if (lockoutMinutes <= 0) {
            throw new IllegalArgumentException("auth.login.lockout-minutes must be greater than 0");
        }
        if (attemptsTtlMinutes <= 0) {
            throw new IllegalArgumentException("auth.login.attempts-ttl-minutes must be greater than 0");
        }

        this.redisTemplate = redisTemplate;
        this.maxAttempts = maxAttempts;
        this.lockoutDuration = Duration.ofMinutes(lockoutMinutes);
        this.attemptsTtl = Duration.ofMinutes(attemptsTtlMinutes);
    }

    /**
     * Checks whether the given IP is locked out for this specific email.
     * <p>
     * Lockout is intentionally scoped to the (IP, email) pair so that an attacker
     * sending failed attempts from their own address cannot lock out the real owner
     * logging in from a different IP. Each IP accumulates its own failed-attempt counter
     * and its own lockout flag independently.
     *
     * @param clientIp resolved client IP (from {@link RateLimitService#getClientIp})
     * @param email    email address being authenticated
     * @throws ResponseStatusException 423 (Locked) if this IP is locked out for this email
     */
    public void checkLockout(String clientIp, String email) {
        String lockoutKey = getLockoutKey(clientIp, email);
        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockoutKey))) {
            long ttlSeconds = redisTemplate.getExpire(lockoutKey, TimeUnit.SECONDS);
            long remainingMinutes = Math.max(1, (long) Math.ceil(ttlSeconds / 60.0));
            throw new ResponseStatusException(HttpStatus.LOCKED,
                    String.format("Zbyt wiele nieudanych prób logowania. " +
                            "Spróbuj ponownie za %d minut.", remainingMinutes));
        }
    }

    /**
     * Records a failed login attempt for the (IP, email) pair and locks the IP for that
     * email once {@code maxAttempts} is reached.
     * <p>
     * Uses an atomic Lua script so the counter always carries a TTL, even under
     * concurrent load or partial failure between INCR and EXPIRE.
     *
     * @param clientIp resolved client IP
     * @param email    email address being authenticated
     */
    public void recordFailedAttempt(String clientIp, String email) {
        String attemptsKey = getAttemptsKey(clientIp, email);

        Long attempts = redisTemplate.execute(INCR_WITH_EXPIRE,
                List.of(attemptsKey),
                String.valueOf(attemptsTtl.getSeconds()));

        if (attempts != null && attempts >= maxAttempts) {
            lockIpForEmail(clientIp, email);
            log.warn("IP locked out for email due to too many failed attempts: ip={}, email={}", clientIp, email);
        }
    }

    /**
     * Clears the failed-attempt counter for this (IP, email) pair after a successful login.
     *
     * @param clientIp resolved client IP
     * @param email    email address that just authenticated successfully
     */
    public void clearAttempts(String clientIp, String email) {
        redisTemplate.delete(getAttemptsKey(clientIp, email));
    }

    private void lockIpForEmail(String clientIp, String email) {
        redisTemplate.opsForValue().set(getLockoutKey(clientIp, email), "1", lockoutDuration);
        redisTemplate.delete(getAttemptsKey(clientIp, email));
    }

    private String getAttemptsKey(String clientIp, String email) {
        return ATTEMPTS_PREFIX + clientIp + ":" + email;
    }

    private String getLockoutKey(String clientIp, String email) {
        return LOCKOUT_PREFIX + clientIp + ":" + email;
    }
}
