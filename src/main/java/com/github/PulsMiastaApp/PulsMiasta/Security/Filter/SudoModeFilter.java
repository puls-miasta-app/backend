package com.github.PulsMiastaApp.PulsMiasta.Security.Filter;

import com.github.PulsMiastaApp.PulsMiasta.Security.Annotation.RequireSudoMode;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.SudoModeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Spring MVC interceptor that enforces {@link RequireSudoMode} on controller methods and classes.
 *
 * <p>Runs as a {@link HandlerInterceptor} — after Spring MVC resolves the handler but before
 * the controller method executes. This is critical: the previous implementation used a servlet
 * {@code Filter}, which ran before {@code DispatcherServlet} resolved the handler, making
 * {@code HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE} always {@code null} and the check inert.
 *
 * <p>Registration: {@code WebMvcConfig#addInterceptors}.
 */
@Component
@RequiredArgsConstructor
public class SudoModeFilter implements HandlerInterceptor {

    private final SudoModeService sudoModeService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RequireSudoMode annotation = handlerMethod.getMethodAnnotation(RequireSudoMode.class);
        if (annotation == null) {
            annotation = handlerMethod.getBeanType().getAnnotation(RequireSudoMode.class);
        }

        if (annotation == null) {
            return true;
        }

        String sessionToken = AuthTokenFilter.extractCookie(request, AuthTokenFilter.SESSION_COOKIE_NAME)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));

        if (!sudoModeService.isSudoModeActive(sessionToken)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sudo mode required");
        }

        return true;
    }
}
