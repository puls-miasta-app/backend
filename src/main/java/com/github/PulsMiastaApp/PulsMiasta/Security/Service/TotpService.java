package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Provides TOTP (Time-based One-Time Password, RFC 6238) operations.
 * <p>
 * Compatible with any authenticator app that supports the otpauth:// URI scheme
 * (Google Authenticator, Authy, Microsoft Authenticator, etc.).
 * <p>
 * Verification uses a ±1 time-step window (i.e. ±30 seconds) to tolerate
 * minor clock drift between server and client device.
 * <p>
 * Includes replay protection: tracks recently used codes to prevent reuse
 * within the same time window.
 */
@Slf4j
@Service
public class TotpService {

    private static final String ISSUER = "PulsMiasta";
    private static final int SECRET_LENGTH = 32;
    private static final String USED_CODE_PREFIX = "totp_used:";
    private static final long USED_CODE_TTL_MINUTES = 2;

    private final SecretGenerator secretGenerator;
    private final CodeVerifier codeVerifier;
    private final StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    public TotpService(StringRedisTemplate redisTemplate) {
        this.secretGenerator = new DefaultSecretGenerator(SECRET_LENGTH);
        this.redisTemplate = redisTemplate;

        TimeProvider timeProvider = new SystemTimeProvider();
        CodeGenerator codeGenerator = new DefaultCodeGenerator();
        DefaultCodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
        verifier.setTimePeriod(30);
        verifier.setAllowedTimePeriodDiscrepancy(1);
        this.codeVerifier = verifier;
    }

    /**
     * Generates a new random Base32-encoded TOTP secret.
     * Store this in {@link com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User#getTotpSecret()}.
     */
    public String generateSecret() {
        return secretGenerator.generate();
    }

    /**
     * Builds the {@code otpauth://} URI that the client uses to display a QR code
     * or deep-link into an authenticator app.
     *
     * @param email  the user's email — used as the account label
     * @param secret the Base32 TOTP secret
     * @return otpauth URI string
     */
    public String buildOtpAuthUri(String email, String secret) {
        String label = URLEncoder.encode(ISSUER + ":" + email, StandardCharsets.UTF_8);
        String issuerEncoded = URLEncoder.encode(ISSUER, StandardCharsets.UTF_8);
        return "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + issuerEncoded
                + "&algorithm=SHA1&digits=6&period=30";
    }

    /**
     * Verifies a 6-digit TOTP code against the stored secret.
     * Accepts codes from the previous, current, and next 30-second window.
     * <p>
     * Includes replay protection: checks if the code has already been used
     * and rejects it if it has been used within the recent time window.
     *
     * @param secret the Base32 secret stored for the user
     * @param code   the 6-digit code entered by the user
     * @return {@code true} if the code is valid and not replayed
     */
    public boolean isValidCode(String secret, String code) {
        if (redisTemplate != null && isCodeUsed(secret, code)) {
            log.debug("TOTP code replay detected for secret={}", secret);
            return false;
        }

        if (!codeVerifier.isValidCode(secret, code)) {
            return false;
        }

        if (redisTemplate != null) {
            markCodeAsUsed(secret, code);
        }
        return true;
    }

    private boolean isCodeUsed(String secret, String code) {
        String key = USED_CODE_PREFIX + secret + ":" + code;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    private void markCodeAsUsed(String secret, String code) {
        String key = USED_CODE_PREFIX + secret + ":" + code;
        redisTemplate.opsForValue().set(key, "1", Duration.ofMinutes(USED_CODE_TTL_MINUTES));
    }
}
