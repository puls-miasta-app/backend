package com.github.PulsMiastaApp.PulsMiasta.Security.Filter;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.AuthResult;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

@Component
public class AuthTokenFilter extends OncePerRequestFilter {

    public static final String SESSION_COOKIE_NAME = "auth_token";
    public static final String REMEMBER_ME_COOKIE_NAME = "remember_me";

    // Konfigurowalne cookie flags — defaulty dev-friendly (http localhost).
    // Prod: ustaw auth.cookie.secure=true i auth.cookie.same-site=Strict (lub None gdy cross-site).
    private static volatile boolean cookieSecure = false;
    private static volatile String cookieSameSite = "Lax";

    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final long sessionTtlSeconds;

    public AuthTokenFilter(
            TokenService tokenService,
            UserRepository userRepository,
            @Value("${auth.session.ttl-minutes}") long sessionMinutes,
            @Value("${auth.cookie.secure:false}") boolean cookieSecureProp,
            @Value("${auth.cookie.same-site:Lax}") String cookieSameSiteProp
    ) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.sessionTtlSeconds = sessionMinutes * 60;
        AuthTokenFilter.cookieSecure = cookieSecureProp;
        AuthTokenFilter.cookieSameSite = cookieSameSiteProp;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        Optional<String> sessionToken = extractCookie(request, SESSION_COOKIE_NAME);

        // 1. Try active session — slide TTL on hit
        Optional<Long> userId = sessionToken.flatMap(tokenService::getUserIdAndSlide);

        // 2. Session expired/missing — try remember-me to renew
        if (userId.isEmpty()) {
            Optional<String> rememberMeToken = extractCookie(request, REMEMBER_ME_COOKIE_NAME);
            if (rememberMeToken.isPresent()) {
                Optional<String> newSessionToken = tokenService.renewSessionFromRememberMe(rememberMeToken.get());
                if (newSessionToken.isPresent()) {
                    userId = tokenService.getUserIdAndSlide(newSessionToken.get());
                    addCookie(response, SESSION_COOKIE_NAME, newSessionToken.get(), (int) sessionTtlSeconds);
                }
            }
        }

        // 3. Resolve userId → User → AuthPrincipal → SecurityContext
        // Always use findByIdWithGeo — single query covers both regular users and admins.
        // For regular users the geo JOIN FETCH returns empty collections (no overhead).
        userId.flatMap(id -> userRepository.findByIdWithGeo(id))
                .map(AuthPrincipal::from)
                .ifPresent(principal -> {
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            principal.getAuthorities()
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });

        filterChain.doFilter(request, response);
    }

    // -------------------------------------------------------------------------
    // Cookie helpers (also used by AuthController via constants)
    // -------------------------------------------------------------------------

    public static void addCookie(HttpServletResponse response, String name, String value, int maxAgeSeconds) {
        Cookie cookie = new Cookie(name, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/");
        cookie.setAttribute("SameSite", cookieSameSite);
        cookie.setMaxAge(maxAgeSeconds);
        response.addCookie(cookie);
    }

    public static void clearCookie(HttpServletResponse response, String name) {
        Cookie cookie = new Cookie(name, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/");
        cookie.setAttribute("SameSite", cookieSameSite);
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    /**
     * Extracts a cookie value by name. Returns {@link Optional#empty()} when the cookie jar is
     * absent or the named cookie is not present.
     */
    public static Optional<String> extractCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return Optional.empty();
        return Arrays.stream(request.getCookies())
                .filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    /**
     * Sets the session cookie and, when requested, the remember-me cookie on the response.
     *
     * @param isMobile {@code true} when the client is a mobile app (longer remember-me TTL)
     */
    public static void applyAuthCookies(HttpServletResponse response, AuthResult result,
                                        boolean rememberMe, boolean isMobile,
                                        long sessionTtlMinutes, long rememberMeWebDays,
                                        long rememberMeMobileDays) {
        addCookie(response, SESSION_COOKIE_NAME, result.sessionToken(),
                (int) (sessionTtlMinutes * 60));
        if (rememberMe && result.rememberMeToken() != null) {
            long days = isMobile ? rememberMeMobileDays : rememberMeWebDays;
            addCookie(response, REMEMBER_ME_COOKIE_NAME, result.rememberMeToken(),
                    (int) (days * 24 * 60 * 60));
        }
    }
}
