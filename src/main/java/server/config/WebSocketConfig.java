package server.config;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import server.realtime.BrowserWebSocketHandler;

@RequiredArgsConstructor
@Configuration
@EnableWebSocket
/** Agent 제어 채널과 브라우저 상태 채널의 URL을 등록한다. */
public class WebSocketConfig implements WebSocketConfigurer {
    private final BrowserWebSocketHandler browserWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(browserWebSocketHandler, "/lnis/ws/status").setAllowedOrigins("*");
    }
}
