package com.github.PulsMiastaApp.PulsMiasta.Controller;

import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ClientType;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.ErrorResponse;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.LoginRequest;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.RegisterRequest;
import com.github.PulsMiastaApp.PulsMiasta.Controller.DTO.SuccessResponse;
import com.github.PulsMiastaApp.PulsMiasta.Security.Filter.AuthTokenFilter;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.AuthResult;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.AuthService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Optional;

@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Value("${auth.session.ttl-minutes}")
    private long sessionTtlMinutes;

    @Value("${auth.remember-me.web.ttl-days}")
    private long rememberMeWebDays;

    @Value("${auth.remember-me.mobile.ttl-days}")
    private long rememberMeMobileDays;

    @PostMapping("/register")
    @ApiResponses({
            @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = RegisterSuccessResponse.class))),
            @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<SuccessResponse<String>> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletResponse response) {

        AuthResult result = authService.register(request);
        applyAuthCookies(response, result, request.rememberMe(), request.clientType());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SuccessResponse.of("Registered successfully"));
    }

    @PostMapping("/login")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = LoginSuccessResponse.class))),
            @ApiResponse(responseCode = "401", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<SuccessResponse<String>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {

        AuthResult result = authService.login(request);
        applyAuthCookies(response, result, request.rememberMe(), request.clientType());

        return ResponseEntity.ok(SuccessResponse.of("Logged in successfully"));
    }

    @PostMapping("/logout")
    @ApiResponses({
            @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = LogoutSuccessResponse.class)))
    })
    public ResponseEntity<SuccessResponse<String>> logout(
            HttpServletRequest request,
            HttpServletResponse response) {

        String sessionToken    = extractCookie(request, AuthTokenFilter.SESSION_COOKIE_NAME).orElse(null);
        String rememberMeToken = extractCookie(request, AuthTokenFilter.REMEMBER_ME_COOKIE_NAME).orElse(null);

        authService.logout(sessionToken, rememberMeToken);

        AuthTokenFilter.clearCookie(response, AuthTokenFilter.SESSION_COOKIE_NAME);
        AuthTokenFilter.clearCookie(response, AuthTokenFilter.REMEMBER_ME_COOKIE_NAME);

        return ResponseEntity.ok(SuccessResponse.of("Logged out successfully"));
    }

    // -------------------------------------------------------------------------
    // OpenAPI schema helpers — concrete types so springdoc resolves T correctly
    // -------------------------------------------------------------------------

    @Schema(name = "RegisterSuccessResponse")
    private static class RegisterSuccessResponse extends SuccessResponse<String> {
        public RegisterSuccessResponse() { super(true, "Registered successfully"); }
    }

    @Schema(name = "LoginSuccessResponse")
    private static class LoginSuccessResponse extends SuccessResponse<String> {
        public LoginSuccessResponse() { super(true, "Logged in successfully"); }
    }

    @Schema(name = "LogoutSuccessResponse")
    private static class LogoutSuccessResponse extends SuccessResponse<String> {
        public LogoutSuccessResponse() { super(true, "Logged out successfully"); }
    }

    // -------------------------------------------------------------------------

    private void applyAuthCookies(HttpServletResponse response, AuthResult result,
                                   boolean rememberMe, ClientType clientType) {
        int sessionMaxAge = (int) (sessionTtlMinutes * 60);
        AuthTokenFilter.addCookie(response, AuthTokenFilter.SESSION_COOKIE_NAME,
                result.sessionToken(), sessionMaxAge);

        if (rememberMe && result.rememberMeToken() != null) {
            long days = clientType == ClientType.MOBILE ? rememberMeMobileDays : rememberMeWebDays;
            int rememberMaxAge = (int) (days * 24 * 60 * 60);
            AuthTokenFilter.addCookie(response, AuthTokenFilter.REMEMBER_ME_COOKIE_NAME,
                    result.rememberMeToken(), rememberMaxAge);
        }
    }

    private Optional<String> extractCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return Optional.empty();
        return Arrays.stream(request.getCookies())
                .filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }
}
