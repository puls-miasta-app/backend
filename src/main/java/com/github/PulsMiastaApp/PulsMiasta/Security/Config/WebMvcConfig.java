package com.github.PulsMiastaApp.PulsMiasta.Security.Config;

import com.github.PulsMiastaApp.PulsMiasta.Security.Interceptor.SudoModeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC configuration — registers HandlerInterceptors.
 *
 * <p>{@link SudoModeInterceptor} runs as an interceptor (not a servlet filter) so that
 * Spring MVC has already resolved the handler method when the sudo check executes.
 * Only at that point is the {@code @RequireSudoMode} annotation readable.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final SudoModeInterceptor sudoModeInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(sudoModeInterceptor).addPathPatterns("/v1/**");
    }
}
