package com.github.PulsMiastaApp.PulsMiasta.Security.Service;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Provides TOTP (Time-based One-Time Password, RFC 6238) operations.
 * <p>
 * Compatible with any authenticator app that supports the otpauth:// URI scheme
 * (Google Authenticator, Authy, Microsoft Authenticator, etc.).
 * <p>
 * Verification uses a ±1 time-step window (i.e. ±30 seconds) to tolerate
 * minor clock drift between server and client device.
 */
@Service
public class TotpService {

    private static final String ISSUER = "PulsMiasta";
    private static final int SECRET_LENGTH = 32;

    private final SecretGenerator secretGenerator;
    private final CodeVerifier codeVerifier;

    public TotpService() {
        this.secretGenerator = new DefaultSecretGenerator(SECRET_LENGTH);

        TimeProvider timeProvider = new SystemTimeProvider();
        CodeGenerator codeGenerator = new DefaultCodeGenerator();
        DefaultCodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
        verifier.setTimePeriod(30);
        verifier.setAllowedTimePeriodDiscrepancy(1); // ±30 seconds tolerance
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
     *
     * @param secret the Base32 secret stored for the user
     * @param code   the 6-digit code entered by the user
     * @return {@code true} if the code is valid
     */
    public boolean isValidCode(String secret, String code) {
        return codeVerifier.isValidCode(secret, code);
    }
}
