package com.github.PulsMiastaApp.PulsMiasta.Config;

import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.TokenService;
import com.github.PulsMiastaApp.PulsMiasta.Security.WebSocket.WebSocketChannelInterceptor;
import jakarta.servlet.http.Cookie;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final WebSocketChannelInterceptor channelInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/chat")
                .addInterceptors(authHandshakeInterceptor())
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // In-memory broker dla tematów /topic (broadcast do wszystkich subskrybentów)
        config.enableSimpleBroker("/topic");
        // Prefix dla wiadomości wysyłanych przez klientów (nie używany — REST do wysyłania)
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(channelInterceptor);
    }

    /**
     * Interceptor HTTP handshake — wyciąga token z ciasteczka i zapisuje AuthPrincipal
     * w atrybutach sesji WebSocket. Potem ChannelInterceptor odczyta go ze STOMP CONNECT.
     */
    private HandshakeInterceptor authHandshakeInterceptor() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request,
                                           ServerHttpResponse response,
                                           WebSocketHandler wsHandler,
                                           Map<String, Object> attributes) {
                if (request instanceof ServletServerHttpRequest servletRequest) {
                    Cookie[] cookies = servletRequest.getServletRequest().getCookies();
                    if (cookies != null) {
                        for (Cookie cookie : cookies) {
                            if ("auth_token".equals(cookie.getName())) {
                                Optional<Long> userId = tokenService.getUserIdAndSlide(cookie.getValue());
                                userId.flatMap(id -> userRepository.findByIdWithGeo(id))
                                        .map(AuthPrincipal::from)
                                        .ifPresent(principal -> {
                                            attributes.put("principal", principal);
                                            log.debug("WS handshake auth OK — userId={}", principal.id());
                                        });
                                break;
                            }
                        }
                    }
                }
                // Zwróć true zawsze — auth weryfikujemy w ChannelInterceptor (STOMP CONNECT)
                return true;
            }

            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Exception ex) {
                if (ex != null) log.warn("WS handshake error: {}", ex.getMessage());
            }
        };
    }
}
