package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
public class EmailVerificationService {

    private static final String EMAIL_VERIFY_PREFIX = "email_verify:";
    private static final String TEMPLATE_PATH = "templates/email/verify-email.html";

    private final JavaMailSender mailSender;
    private final RedisTemplate<String, Long> redisTemplate;
    private final UserRepository userRepository;
    private final Duration tokenTtl;
    private final String appBaseUrl;
    private final String mailFrom;

    public EmailVerificationService(
            JavaMailSender mailSender,
            RedisTemplate<String, Long> redisTemplate,
            UserRepository userRepository,
            @Value("${auth.email-verification.ttl-hours}") long ttlHours,
            @Value("${app.base-url}") String appBaseUrl,
            @Value("${app.mail.from}") String mailFrom
    ) {
        this.mailSender = mailSender;
        this.redisTemplate = redisTemplate;
        this.userRepository = userRepository;
        this.tokenTtl = Duration.ofHours(ttlHours);
        this.appBaseUrl = appBaseUrl;
        this.mailFrom = mailFrom;
    }

    @Async
    public void sendVerificationEmail(User user) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(EMAIL_VERIFY_PREFIX + token, user.getId(), tokenTtl);

        try {
            String verifyUrl = appBaseUrl + "/api/v1/auth/verify-email?token=" + token;
            sendHtmlEmail(user.getEmail(), user.getFirstName(), verifyUrl);
        } catch (Exception e) {
            log.error("Failed to send verification email to {}: {}", user.getEmail(), e.getMessage());
        }
    }

    public void verifyToken(String token) {
        Long userId = redisTemplate.opsForValue().get(EMAIL_VERIFY_PREFIX + token);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired verification token");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        user.setEmailVerified(true);
        userRepository.save(user);
        redisTemplate.delete(EMAIL_VERIFY_PREFIX + token);
    }

    private void sendHtmlEmail(String to, String firstName, String verifyUrl) throws MessagingException, IOException {
        String html = loadTemplate()
                .replace("{{firstName}}", firstName)
                .replace("{{verifyUrl}}", verifyUrl);

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(mailFrom);
        helper.setTo(to);
        helper.setSubject("Potwierdź swój adres e-mail — PulsMiasta");
        helper.setText(html, true);

        mailSender.send(message);
        log.info("Verification email sent to {}", to);
    }

    private String loadTemplate() throws IOException {
        ClassPathResource resource = new ClassPathResource(TEMPLATE_PATH);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}
