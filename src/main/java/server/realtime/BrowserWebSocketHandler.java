package server.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
@Component
/** 브라우저 상태 구독 세션에 동일한 실시간 이벤트를 방송한다. */
public class BrowserWebSocketHandler extends TextWebSocketHandler {
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    public void broadcast(Object event) {
        try {
            String body = objectMapper.writeValueAsString(event);
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(body));
                    }
                }
            }
        } catch (Exception ignored) {
            // 개별 브라우저 연결 실패가 시험 세션 처리를 중단시키지 않도록 무시한다.
        }
    }
}
