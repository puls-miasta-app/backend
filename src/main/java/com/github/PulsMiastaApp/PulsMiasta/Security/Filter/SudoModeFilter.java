package com.github.PulsMiastaApp.PulsMiasta.Security.Filter;

import com.github.PulsMiastaApp.PulsMiasta.Security.Annotation.RequireSudoMode;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.SudoModeService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class SudoModeFilter implements Filter {

    private final SudoModeService sudoModeService;
    private final HandlerExceptionResolver handlerExceptionResolver;

    @Override
    public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        Object handler = httpRequest.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);

        if (handler instanceof HandlerMethod handlerMethod) {
            RequireSudoMode annotation = handlerMethod.getMethodAnnotation(RequireSudoMode.class);
            if (annotation == null) {
                annotation = handlerMethod.getBeanType().getAnnotation(RequireSudoMode.class);
            }

            if (annotation != null) {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication == null) {
                    handlerExceptionResolver.resolveException(httpRequest, httpResponse, null,
                            new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
                    return;
                }

                Optional<String> sessionToken = extractCookie(httpRequest, AuthTokenFilter.SESSION_COOKIE_NAME);
                if (sessionToken.isEmpty()) {
                    handlerExceptionResolver.resolveException(httpRequest, httpResponse, null,
                            new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
                    return;
                }

                if (!sudoModeService.isSudoModeActive(sessionToken.get())) {
                    handlerExceptionResolver.resolveException(httpRequest, httpResponse, null,
                            new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN, "Sudo mode required"));
                    return;
                }
            }
        }

        chain.doFilter(request, response);
    }

    private Optional<String> extractCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return Optional.empty();
        return Arrays.stream(request.getCookies())
                .filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }
}
