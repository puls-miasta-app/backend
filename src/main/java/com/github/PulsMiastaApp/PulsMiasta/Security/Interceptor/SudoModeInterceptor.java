package com.github.PulsMiastaApp.PulsMiasta.Security.Interceptor;

import com.github.PulsMiastaApp.PulsMiasta.Security.Annotation.RequireSudoMode;
import com.github.PulsMiastaApp.PulsMiasta.Security.Filter.AuthTokenFilter;
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
 * Enforces {@link RequireSudoMode} on controller methods and classes.
 *
 * <p>Runs as a {@link HandlerInterceptor} so that Spring MVC has already resolved the handler
 * method when the check executes. A servlet {@code Filter} would run before
 * {@code DispatcherServlet} resolves the handler, making the annotation unreadable.
 *
 * <p>Registration: {@link com.github.PulsMiastaApp.PulsMiasta.Security.Config.WebMvcConfig}.
 */
@Component
@RequiredArgsConstructor
public class SudoModeInterceptor implements HandlerInterceptor {

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
