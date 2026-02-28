package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

/**
 * Manages 6-digit email OTP codes for sudo mode activation.
 * <p>
 * Redis key layout:
 * <ul>
 *   <li>{@code sudo_otp:{userId}} → the OTP code (TTL = configurable, default 10 min)</li>
 *   <li>{@code sudo_otp_sent:{userId}} → timestamp of last send (TTL = cooldown, default 60 s)</li>
 *   <li>{@code sudo_otp_attempts:{userId}} → failure count (TTL = code TTL)</li>
 * </ul>
 */
@Slf4j
@Service
public class SudoOtpService {

    private static final String OTP_PREFIX = "sudo_otp:";
    private static final String SENT_PREFIX = "sudo_otp_sent:";
    private static final String ATTEMPTS_PREFIX = "sudo_otp_attempts:";
    private static final String TEMPLATE_PATH = "templates/email/sudo-otp.html";

    private final JavaMailSender mailSender;
    private final StringRedisTemplate redisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    private final Duration otpTtl;
    private final Duration cooldown;
    private final int maxAttempts;
    private final String mailFrom;

    public SudoOtpService(
            JavaMailSender mailSender,
            StringRedisTemplate redisTemplate,
            @Value("${auth.sudo-otp.ttl-minutes:10}") long ttlMinutes,
            @Value("${auth.sudo-otp.cooldown-seconds:60}") long cooldownSeconds,
            @Value("${auth.sudo-otp.max-attempts:3}") int maxAttempts,
            @Value("${app.mail.from}") String mailFrom
    ) {
        if (ttlMinutes <= 0) {
            throw new IllegalArgumentException("auth.sudo-otp.ttl-minutes must be greater than 0");
        }
        if (cooldownSeconds <= 0) {
            throw new IllegalArgumentException("auth.sudo-otp.cooldown-seconds must be greater than 0");
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("auth.sudo-otp.max-attempts must be greater than 0");
        }

        this.mailSender = mailSender;
        this.redisTemplate = redisTemplate;
        this.otpTtl = Duration.ofMinutes(ttlMinutes);
        this.cooldown = Duration.ofSeconds(cooldownSeconds);
        this.maxAttempts = maxAttempts;
        this.mailFrom = mailFrom;
    }

    /**
     * Generates and emails a 6-digit OTP for sudo mode activation.
     * Enforces a per-user cooldown to prevent email flooding.
     *
     * @param userId    the authenticated user's ID
     * @param email     the email address to send the code to
     * @param firstName the user's first name (used in email greeting)
     */
    @Async
    public void sendOtp(Long userId, String email, String firstName) {
        String sentKey = SENT_PREFIX + userId;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(sentKey))) {
            log.debug("Sudo OTP send rejected — cooldown active for userId={}", userId);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Please wait before requesting another code");
        }

        String code = generateCode();
        redisTemplate.opsForValue().set(OTP_PREFIX + userId, code, otpTtl);
        redisTemplate.opsForValue().set(SENT_PREFIX + userId, Instant.now().toString(), cooldown);
        redisTemplate.delete(ATTEMPTS_PREFIX + userId);

        try {
            sendEmail(email, firstName, code);
        } catch (Exception e) {
            log.error("Failed to send sudo OTP email to userId={}: {}", userId, e.getMessage());
        }
    }

    /**
     * Verifies the OTP submitted by the user.
     * Increments the failure counter on mismatch; deletes the code on success.
     * <p>
     * Uses Redis INCR for atomic increment to prevent race conditions in concurrent requests.
     *
     * @param userId the authenticated user's ID
     * @param code   the 6-digit code submitted by the user
     * @throws ResponseStatusException 400 if code invalid/expired, 429 if too many attempts
     */
    public void verifyOtp(Long userId, String code) {
        String attemptsKey = ATTEMPTS_PREFIX + userId;

        Long attempts = redisTemplate.opsForValue().increment(attemptsKey);

        if (attempts == 1) {
            redisTemplate.expire(attemptsKey, otpTtl);
        }

        if (attempts > maxAttempts) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many incorrect attempts. Please request a new code.");
        }

        String stored = redisTemplate.opsForValue().get(OTP_PREFIX + userId);
        if (stored == null) {
            redisTemplate.opsForValue().decrement(attemptsKey);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Code expired or not requested");
        }

        if (!stored.equals(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid code");
        }

        // Success — clean up all keys
        redisTemplate.delete(OTP_PREFIX + userId);
        redisTemplate.delete(ATTEMPTS_PREFIX + userId);
    }

    private String generateCode() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }

    private void sendEmail(String to, String firstName, String code) throws MessagingException, IOException {
        String html = loadTemplate()
                .replace("{{firstName}}", firstName)
                .replace("{{code}}", code)
                .replace("{{minutes}}", String.valueOf(otpTtl.toMinutes()));

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(mailFrom);
        helper.setTo(to);
        helper.setSubject("Kod weryfikacyjny sudo — PulsMiasta");
        helper.setText(html, true);

        mailSender.send(message);
        log.info("Sudo OTP email sent to {}", to);
    }

    private String loadTemplate() throws IOException {
        ClassPathResource resource = new ClassPathResource(TEMPLATE_PATH);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}
