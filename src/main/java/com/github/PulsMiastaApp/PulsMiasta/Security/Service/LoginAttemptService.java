package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class LoginAttemptService {

    private static final String ATTEMPTS_PREFIX = "login_attempts:";
    private static final String LOCKOUT_PREFIX = "account_lockout:";

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
     * Checks if an account is currently locked out due to too many failed login attempts.
     *
     * @param email the email address to check
     * @throws ResponseStatusException 423 (Locked) if the account is locked out
     */
    public void checkLockout(String email) {
        String lockoutKey = LOCKOUT_PREFIX + email;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockoutKey))) {
            long remainingMinutes = redisTemplate.getExpire(lockoutKey, TimeUnit.MINUTES);
            throw new ResponseStatusException(HttpStatus.LOCKED,
                    String.format("Account locked due to too many failed login attempts. " +
                            "Please try again in %d minutes.", remainingMinutes));
        }
    }

    /**
     * Records a failed login attempt and locks the account if threshold is reached.
     * <p>
     * Uses Redis INCR for atomic increment to prevent race conditions in concurrent requests.
     *
     * @param email the email address for which the login attempt failed
     */
    public void recordFailedAttempt(String email) {
        String attemptsKey = ATTEMPTS_PREFIX + email;

        Long attempts = redisTemplate.opsForValue().increment(attemptsKey);

        if (attempts == 1) {
            redisTemplate.expire(attemptsKey, attemptsTtl);
        }

        if (attempts >= maxAttempts) {
            lockAccount(email);
            log.warn("Account locked out due to too many failed attempts: email={}", email);
        }
    }

    /**
     * Clears failed login attempts after a successful login.
     *
     * @param email the email address to clear
     */
    public void clearAttempts(String email) {
        redisTemplate.delete(ATTEMPTS_PREFIX + email);
    }

    /**
     * Locks an account for the configured duration.
     *
     * @param email the email address to lock
     */
    private void lockAccount(String email) {
        String lockoutKey = LOCKOUT_PREFIX + email;
        redisTemplate.opsForValue().set(lockoutKey, "1", lockoutDuration);
        redisTemplate.delete(ATTEMPTS_PREFIX + email);
    }
}