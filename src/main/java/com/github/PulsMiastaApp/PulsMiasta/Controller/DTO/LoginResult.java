package com.github.PulsMiastaApp.PulsMiasta.Controller.DTO;

/**
 * Sealed result type returned by the login use-case.
 * <p>
 * Two outcomes are possible:
 * <ul>
 *   <li>{@link SessionGranted} — credentials accepted, full session created.</li>
 *   <li>{@link TwoFactorRequired} — password correct, but a TOTP code must be
 *       submitted to {@code POST /auth/login/totp} before a session is granted.</li>
 * </ul>
 */
public sealed interface LoginResult {

    /**
     * Login completed — session (and optionally remember-me) tokens are ready.
     */
    record SessionGranted(String sessionToken, String rememberMeToken) implements LoginResult {
    }

    /**
     * First factor accepted, second factor (TOTP) still required.
     *
     * @param pendingToken a short-lived opaque token the client echoes in the TOTP request
     */
    record TwoFactorRequired(String pendingToken) implements LoginResult {
    }
}
