package com.github.PulsMiastaApp.PulsMiasta.Security.Filter;

import com.github.PulsMiastaApp.PulsMiasta.Model.Entities.Jpa.User;
import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthTokenFilterTest {

    @Mock
    private TokenService tokenService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private AuthTokenFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilter_withValidSessionToken_shouldAuthenticateAndSlideTtl() throws Exception {
        String token = "valid-session-token";

        when(request.getCookies()).thenReturn(new Cookie[]{
                new Cookie(AuthTokenFilter.SESSION_COOKIE_NAME, token)
        });
        when(tokenService.getUserIdAndSlide(token)).thenReturn(Optional.of(123L));

        User user = new User();
        user.setId(123L);
        user.setEmail("jan@example.com");
        user.setFirstName("Jan");
        user.setLastName("Kowalski");
        user.setRole("USER");

        when(userRepository.findById(123L)).thenReturn(Optional.of(user));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        AuthPrincipal principal = (AuthPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal.id()).isEqualTo(123L);
        assertThat(principal.email()).isEqualTo("jan@example.com");
        verify(tokenService).getUserIdAndSlide(token);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilter_withExpiredSessionAndValidRememberMe_shouldCreateNewSession() throws Exception {
        String expiredSessionToken = "expired-token";
        String validRememberMeToken = "valid-remember-me";
        String newSessionToken = "new-session-token";

        when(request.getCookies()).thenReturn(new Cookie[]{
                new Cookie(AuthTokenFilter.SESSION_COOKIE_NAME, expiredSessionToken),
                new Cookie(AuthTokenFilter.REMEMBER_ME_COOKIE_NAME, validRememberMeToken)
        });
        when(tokenService.getUserIdAndSlide(expiredSessionToken)).thenReturn(Optional.empty());
        when(tokenService.renewSessionFromRememberMe(validRememberMeToken)).thenReturn(Optional.of(newSessionToken));
        when(tokenService.getUserIdAndSlide(newSessionToken)).thenReturn(Optional.of(456L));

        User user = new User();
        user.setId(456L);
        user.setEmail("jan@example.com");
        user.setFirstName("Jan");
        user.setLastName("Kowalski");
        user.setRole("USER");

        when(userRepository.findById(456L)).thenReturn(Optional.of(user));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(tokenService).renewSessionFromRememberMe(validRememberMeToken);
        verify(tokenService).getUserIdAndSlide(newSessionToken);
        verify(response).addCookie(any(Cookie.class));
    }

    @Test
    void doFilter_withNoCookies_shouldNotAuthenticate() throws Exception {
        when(request.getCookies()).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(userRepository);
        verifyNoInteractions(tokenService);
    }

    @Test
    void doFilter_withRememberMeOnly_shouldCreateNewSession() throws Exception {
        String rememberMeToken = "valid-remember-me";
        String newSessionToken = "new-session-token";

        when(request.getCookies()).thenReturn(new Cookie[]{
                new Cookie(AuthTokenFilter.REMEMBER_ME_COOKIE_NAME, rememberMeToken)
        });
        when(tokenService.getUserIdAndSlide(any())).thenReturn(Optional.empty());
        when(tokenService.renewSessionFromRememberMe(rememberMeToken)).thenReturn(Optional.of(newSessionToken));
        when(tokenService.getUserIdAndSlide(newSessionToken)).thenReturn(Optional.of(789L));

        User user = new User();
        user.setId(789L);
        user.setEmail("jan@example.com");
        user.setFirstName("Jan");
        user.setLastName("Kowalski");
        user.setRole("USER");

        when(userRepository.findById(789L)).thenReturn(Optional.of(user));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(tokenService).renewSessionFromRememberMe(rememberMeToken);
        verify(tokenService).getUserIdAndSlide(newSessionToken);
    }
}
