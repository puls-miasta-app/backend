package com.github.PulsMiastaApp.PulsMiasta.Security.Config;

import com.github.PulsMiastaApp.PulsMiasta.Security.Filter.AuthTokenFilter;
import com.github.PulsMiastaApp.PulsMiasta.Security.Filter.SudoModeFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Slf4j
@Configuration
public class SecurityConfig {

    private final AuthTokenFilter authTokenFilter;
    private final SudoModeFilter sudoModeFilter;

    /**
     * Allowed origins for CORS — loaded from {@code cors.allowed-origins} in application.properties.
     * Must be explicit origins (no wildcards) because the API uses credentials (HttpOnly cookies).
     */
    @Value("${cors.allowed-origins}")
    private List<String> corsAllowedOrigins;

    public SecurityConfig(AuthTokenFilter authTokenFilter, SudoModeFilter sudoModeFilter) {
        this.authTokenFilter = authTokenFilter;
        this.sudoModeFilter = sudoModeFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/swagger-ui/index.html",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**").permitAll()
                        // Standard auth (PESEL + password)
                        .requestMatchers("/v1/auth/register", "/v1/auth/login", "/v1/auth/logout",
                                "/v1/auth/verify-email").permitAll()
                        // Passkey — authentication ceremony is public (no session needed to log in)
                        .requestMatchers(
                                "/v1/auth/passkey/authentication/begin",
                                "/v1/auth/passkey/authentication/finish").permitAll()
                        // Passkey — registration and credential management require an active session
                        .requestMatchers(
                                "/v1/auth/passkey/registration/**",
                                "/v1/auth/passkey/credentials/**").authenticated()
                        // Sudo mode — check status, begin/finish verification (needs session)
                        .requestMatchers("/v1/auth/sudo/**").authenticated()
                        .requestMatchers("/v1/test/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(authTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(sudoModeFilter, AuthTokenFilter.class);

        return http.build();
    }

    /**
     * CORS configuration.
     *
     * <p>Key constraints driven by our auth model:
     * <ul>
     *   <li>{@code allowCredentials(true)} — required for HttpOnly cookies to be sent cross-origin.</li>
     *   <li>No wildcard origins — browsers reject {@code allowCredentials + "*"} (CORS spec §3.2.2).</li>
     *   <li>Allowed origins are configured via {@code cors.allowed-origins} in application.properties,
     *       so they can differ between environments without code changes.</li>
     *   <li>Mobile clients (React Native) typically do NOT send CORS preflight — this config
     *       primarily serves the web admin frontend.</li>
     * </ul>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOrigins(corsAllowedOrigins);

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

        // Allow all headers the browser may send (Content-Type, Authorization, etc.)
        config.setAllowedHeaders(List.of("*"));

        // Expose headers the frontend may need to read
        config.setExposedHeaders(List.of("Content-Type", "X-Request-Id"));

        // Must be true — cookies (auth_token, remember_me) are sent with cross-origin requests
        config.setAllowCredentials(true);

        // Cache preflight response for 1 hour (reduces OPTIONS round-trips)
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
