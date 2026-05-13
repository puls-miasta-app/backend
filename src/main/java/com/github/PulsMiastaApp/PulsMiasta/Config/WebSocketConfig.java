package com.github.PulsMiastaApp.PulsMiasta.Config;

import com.github.PulsMiastaApp.PulsMiasta.Repository.UserRepository;
import com.github.PulsMiastaApp.PulsMiasta.Security.Model.AuthPrincipal;
import com.github.PulsMiastaApp.PulsMiasta.Security.Service.WsTokenService;
import com.github.PulsMiastaApp.PulsMiasta.Security.WebSocket.WebSocketChannelInterceptor;
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

@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WsTokenService wsTokenService;
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
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.initialize();
        config.enableSimpleBroker("/topic")
              .setHeartbeatValue(new long[]{25000, 25000})
              .setTaskScheduler(scheduler);
        // Prefix dla wiadomości wysyłanych przez klientów (nie używany — REST do wysyłania)
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(channelInterceptor);
    }

    private HandshakeInterceptor authHandshakeInterceptor() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request,
                                           ServerHttpResponse response,
                                           WebSocketHandler wsHandler,
                                           Map<String, Object> attributes) {
                if (request instanceof ServletServerHttpRequest servletRequest) {
                    var raw = servletRequest.getServletRequest();
                    String token = raw.getParameter("token");
                    if (token != null) {
                        Long userId = wsTokenService.consumeToken(token);
                        if (userId != null) {
                            userRepository.findByIdWithGeo(userId)
                                    .map(AuthPrincipal::from)
                                    .ifPresentOrElse(principal -> {
                                        attributes.put("principal", principal);
                                        log.info("WS handshake auth OK — userId={}", principal.id());
                                    }, () -> log.warn("WS handshake — user not found for userId={}", userId));
                        } else {
                            log.warn("WS handshake — token not found in Redis (expired or already used)");
                        }
                    } else {
                        log.warn("WS handshake — no token in query params");
                    }
                }
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
