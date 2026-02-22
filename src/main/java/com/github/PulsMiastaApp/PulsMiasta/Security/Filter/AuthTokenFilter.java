package com.github.PulsMiastaApp.PulsMiasta.Security.Filter;

import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
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

    public static final String SESSION_COOKIE_NAME     = "auth_token";
    public static final String REMEMBER_ME_COOKIE_NAME = "remember_me";

    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final long sessionTtlSeconds;

    public AuthTokenFilter(
            TokenService tokenService,
            UserRepository userRepository,
            @Value("${auth.session.ttl-minutes}") long sessionMinutes
    ) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.sessionTtlSeconds = sessionMinutes * 60;
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
        userId.flatMap(userRepository::findById)
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
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setAttribute("SameSite", "Strict");
        cookie.setMaxAge(maxAgeSeconds);
        response.addCookie(cookie);
    }

    public static void clearCookie(HttpServletResponse response, String name) {
        Cookie cookie = new Cookie(name, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setAttribute("SameSite", "Strict");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private Optional<String> extractCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return Optional.empty();
        return Arrays.stream(request.getCookies())
                .filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }
}
