package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

import java.util.List;

/**
 * Sealed result type returned by the login use-case.
 * <p>
 * Two outcomes are possible:
 * <ul>
 *   <li>{@link SessionGranted} — credentials accepted, full session created.</li>
 *   <li>{@link TwoFactorRequired} — password correct, but a second factor must be
 *       submitted before a session is granted. The {@code availableMethods} list tells
 *       the client which 2FA methods the user can use.</li>
 * </ul>
 */
public sealed interface LoginResult {

    /**
     * Login completed — session (and optionally remember-me) tokens are ready.
     */
    record SessionGranted(String sessionToken, String rememberMeToken, boolean mustChangePassword) implements LoginResult {
    }

    /**
     * First factor accepted, second factor still required.
     *
     * @param pendingToken     a short-lived opaque token the client echoes in the 2FA request
     * @param availableMethods the 2FA methods available for this user (e.g. "TOTP", "EMAIL_OTP", "PASSKEY")
     */
    record TwoFactorRequired(String pendingToken, List<String> availableMethods) implements LoginResult {
    }
}
