package fruition.core.meeting;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * 브라우저 ↔ document-svc 실시간 받아쓰기 WebSocket.
 *
 * <p>브라우저 WebSocket은 Authorization 헤더를 보낼 수 없어 JWT 대신 일회용 ticket으로 인증하고,
 * 다른 사이트가 사용자의 ticket으로 접속하지 못하도록 Origin을 CORS 허용 목록과 대조한다.
 */
@Configuration
@EnableWebSocket
public class MeetingLiveConfig implements WebSocketConfigurer {
    static final String TICKET_ATTRIBUTE = "meetingLiveTicket";

    private final MeetingLiveHandler handler;
    private final MeetingService meetingService;
    private final List<String> allowedOrigins;

    public MeetingLiveConfig(MeetingLiveHandler handler, MeetingService meetingService,
                             @Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
        this.handler = handler;
        this.meetingService = meetingService;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/meetings/{meetingId}/live")
                .addInterceptors(new TicketInterceptor(meetingService))
                .setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    }

    record TicketInterceptor(MeetingService meetingService) implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            List<String> path = UriComponentsBuilder.fromUri(request.getURI()).build().getPathSegments();
            String meetingId = path.size() >= 3 ? path.get(path.size() - 2) : "";
            String ticket = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("ticket");
            return meetingService.consumeTicket(ticket, meetingId)
                    .map(valid -> {
                        attributes.put(TICKET_ATTRIBUTE, valid);
                        return true;
                    })
                    .orElseGet(() -> {
                        response.setStatusCode(HttpStatus.UNAUTHORIZED);
                        return false;
                    });
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {}
    }
}
