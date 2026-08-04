package net.jojoaddison.config;

import java.security.Principal;
import java.util.List;
import net.jojoaddison.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket, used to tell a clinician that a message has arrived for them.
 *
 * <p><b>The socket carries notifications, not correspondence.</b> Frames are identifiers only,
 * matching the Kafka payload; the client fetches the message itself over HTTP, where the normal
 * authorization check applies. So a stale or wrongly-routed frame leaks nothing.
 *
 * <p><b>Authentication happens on CONNECT, not on the handshake.</b> A browser cannot set an
 * Authorization header on a WebSocket upgrade, which is why {@code /websocket/**} is permitted in
 * {@code SecurityConfiguration}; the token is presented in the CONNECT frame and validated here with
 * the same {@link JwtDecoder} the HTTP side uses. An unauthenticated CONNECT is rejected, so the
 * open handshake buys nothing on its own.
 *
 * <p>The principal name is the JWT subject — the gateway login — which is also what
 * {@code accountId} holds today (see {@code OnboardingService}). That equality is what lets
 * {@code convertAndSendToUser(recipientId, ...)} work without a lookup table. <b>If accountId ever
 * becomes a real user id, this routing breaks silently</b>: frames would be addressed to a principal
 * nobody is connected as, and the symptom is missing notifications rather than an error.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebsocketConfiguration implements WebSocketMessageBrokerConfigurer {

    /** Per-user queue prefix; a client subscribes to {@code /user/queue/messages}. */
    public static final String USER_DESTINATION = "/queue/messages";

    private static final Logger log = LoggerFactory.getLogger(WebsocketConfiguration.class);

    private final JwtDecoder jwtDecoder;

    public WebsocketConfiguration(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // In-memory broker: this is a notification fan-out to connected sessions, not a durable
        // queue. Durability is Kafka's job on the other side of the consumer.
        registry.enableSimpleBroker("/queue", "/topic");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // withSockJS() is deliberately NOT enabled: both nginx layers already pass Upgrade and
        // Connection, and Spring Cloud Gateway proxies the upgrade on the existing
        // /services/professionalservice/** route, so the fallback transports would add a polling
        // path nothing needs.
        registry.addEndpoint("/websocket/messages").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(
            new ChannelInterceptor() {
                @Override
                public Message<?> preSend(Message<?> message, MessageChannel channel) {
                    StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                    if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
                        return message;
                    }
                    Principal principal = authenticate(accessor);
                    if (principal == null) {
                        // Returning null drops the CONNECT, so the session never establishes.
                        log.debug("Rejected STOMP CONNECT with no valid token");
                        return null;
                    }
                    accessor.setUser(principal);
                    return message;
                }
            }
        );
    }

    private Principal authenticate(StompHeaderAccessor accessor) {
        List<String> header = accessor.getNativeHeader("Authorization");
        if (header == null || header.isEmpty()) {
            return null;
        }
        String value = header.get(0);
        String token = value.startsWith("Bearer ") ? value.substring(7) : value;
        try {
            Jwt jwt = jwtDecoder.decode(token);
            String login = jwt.getSubject();
            if (login == null || login.isBlank()) {
                return null;
            }
            // No authorities are attached: this channel grants no access to anything. Everything the
            // client can act on is fetched over HTTP, where its authorities are checked properly.
            return new UsernamePasswordAuthenticationToken(login, null, List.of());
        } catch (JwtException e) {
            log.debug("Rejected STOMP CONNECT: {}", e.getMessage());
            return null;
        }
    }

    /** Kept for symmetry with the HTTP side; unused today but the obvious place to look. */
    public static String currentLoginOrNull() {
        return SecurityUtils.getCurrentUserLogin().orElse(null);
    }
}
