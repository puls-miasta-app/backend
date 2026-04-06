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
 * Manages 6-digit email OTP codes for login 2FA verification.
 * <p>
 * Redis key layout:
 * <ul>
 *   <li>{@code login_otp:{userId}} → the OTP code (TTL = configurable, default 10 min)</li>
 *   <li>{@code login_otp_sent:{userId}} → timestamp of last send (TTL = cooldown, default 60 s)</li>
 *   <li>{@code login_otp_attempts:{userId}} → failure count (TTL = code TTL)</li>
 * </ul>
 */
@Slf4j
@Service
public class LoginOtpService {

    private static final String OTP_PREFIX = "login_otp:";
    private static final String SENT_PREFIX = "login_otp_sent:";
    private static final String ATTEMPTS_PREFIX = "login_otp_attempts:";
    private static final String TEMPLATE_PATH = "templates/email/login-otp.html";

    private final JavaMailSender mailSender;
    private final StringRedisTemplate redisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    private final Duration otpTtl;
    private final Duration cooldown;
    private final int maxAttempts;
    private final String mailFrom;

    public LoginOtpService(
            JavaMailSender mailSender,
            StringRedisTemplate redisTemplate,
            @Value("${auth.login-otp.ttl-minutes:10}") long ttlMinutes,
            @Value("${auth.login-otp.cooldown-seconds:60}") long cooldownSeconds,
            @Value("${auth.login-otp.max-attempts:3}") int maxAttempts,
            @Value("${app.mail.from}") String mailFrom
    ) {
        if (ttlMinutes <= 0) {
            throw new IllegalArgumentException("auth.login-otp.ttl-minutes must be greater than 0");
        }
        if (cooldownSeconds <= 0) {
            throw new IllegalArgumentException("auth.login-otp.cooldown-seconds must be greater than 0");
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("auth.login-otp.max-attempts must be greater than 0");
        }

        this.mailSender = mailSender;
        this.redisTemplate = redisTemplate;
        this.otpTtl = Duration.ofMinutes(ttlMinutes);
        this.cooldown = Duration.ofSeconds(cooldownSeconds);
        this.maxAttempts = maxAttempts;
        this.mailFrom = mailFrom;
    }

    /**
     * Generates and emails a 6-digit OTP for login 2FA verification.
     * Enforces a per-user cooldown to prevent email flooding.
     *
     * @param userId    the user's ID
     * @param email     email address to send the code to
     * @param firstName the user's first name (used in email greeting)
     */
    @Async
    public void sendOtp(Long userId, String email, String firstName) {
        if (userId == null) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        if (email == null || email.trim().isEmpty()) {
            throw new IllegalArgumentException("email cannot be null or empty");
        }
        if (firstName == null || firstName.trim().isEmpty()) {
            throw new IllegalArgumentException("firstName cannot be null or empty");
        }

        String sentKey = SENT_PREFIX + userId;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(sentKey))) {
            log.debug("Login OTP send rejected — cooldown active for userId={}", userId);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Please wait before requesting another code");
        }

        String code = generateCode();

        try {
            sendEmail(email, firstName, code);
            redisTemplate.opsForValue().set(OTP_PREFIX + userId, code, otpTtl);
            redisTemplate.opsForValue().set(SENT_PREFIX + userId, Instant.now().toString(), cooldown);
            redisTemplate.delete(ATTEMPTS_PREFIX + userId);
        } catch (Exception e) {
            log.error("Failed to send login OTP email to userId={}: {}", userId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to send verification code. Please try again.");
        }
    }

    /**
     * Verifies the OTP submitted by the user.
     * Increments the failure counter on mismatch; deletes the code on success.
     *
     * @param userId the user's ID
     * @param code   the 6-digit code submitted by the user
     * @throws ResponseStatusException 400 if code invalid/expired, 429 if too many attempts
     */
    public void verifyOtp(Long userId, String code) {
        if (userId == null) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("code cannot be null or empty");
        }

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
        helper.setSubject("Kod logowania — PulsMiasta");
        helper.setText(html, true);

        mailSender.send(message);
        log.info("Login OTP email sent to {}", to);
    }

    private String loadTemplate() throws IOException {
        ClassPathResource resource = new ClassPathResource(TEMPLATE_PATH);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}
