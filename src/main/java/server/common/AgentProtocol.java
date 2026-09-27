package server.common;

import static server.common.LnisModels.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 기존 브라우저 이벤트와 로컬 수집 명령의 데이터 모델. Agent 전송 프로토콜은 사용하지 않는다. */
public final class AgentProtocol {
    private AgentProtocol() {}

    public enum CommandType {
        LIST_PORTS,
        START_CAPTURE,
        STOP_CAPTURE,
        DTN_STOP_CAPTURE
    }

    /** Agent 상태를 브라우저 화면에 전달할 때 사용하는 실시간 이벤트 종류다. */
    public enum EventType {
        AGENT_STATUS,
        GNSS_STATUS,
        TX_STATUS,
        RX_STATUS,
        SESSION_STATUS,
        RESULT,
        ERROR
    }

    /** 운영체제에서 발견한 COM 포트 한 개의 표시 정보다. */
    @lombok.Value
    @lombok.AllArgsConstructor
    @lombok.Builder
    @lombok.extern.jackson.Jacksonized
    @lombok.experimental.Accessors(fluent = true)
    @com.fasterxml.jackson.annotation.JsonAutoDetect(
            fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
    public static class PortDescriptor {
        /** API 명령에 사용할 Windows 포트 이름이다. 예: {@code COM3}. */
        String name;

        /** 장치 드라이버가 제공하는 사람이 읽을 수 있는 포트 설명이다. */
        String description;
    }

    /** COM 포트 새로고침 명령에 대한 포트 목록 payload다. */
    @lombok.Value
    @lombok.AllArgsConstructor
    @lombok.Builder
    @lombok.extern.jackson.Jacksonized
    @lombok.experimental.Accessors(fluent = true)
    @com.fasterxml.jackson.annotation.JsonAutoDetect(
            fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
    public static class PortList {
        /** 조회 시점에 운영체제가 열거한 직렬 포트 목록이며 없으면 빈 목록이다. */
        List<PortDescriptor> ports;
    }

    /** GNSS/TX/RX 작업의 진행률, 단계, 사용자 메시지와 추가 카운터를 전달한다. */
    @lombok.Value
    @lombok.AllArgsConstructor
    @lombok.Builder
    @lombok.extern.jackson.Jacksonized
    @lombok.experimental.Accessors(fluent = true)
    @com.fasterxml.jackson.annotation.JsonAutoDetect(
            fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
    public static class Progress {
        /** GNSS, TX, RX 등 브라우저에서 분류할 실시간 이벤트 종류다. */
        EventType type;

        /** 해당 작업의 0~100 범위 진행률이다. */
        int percent;

        /** 수신, 복호화, 검증 등 기계가 구분할 현재 단계 이름이다. */
        String stage;

        /** 이벤트 로그에 표시할 사용자용 진행 설명이다. */
        String message;

        /** 프레임 수, byte 수 등 단계별 부가 값을 담는 확장 가능한 맵이다. */
        Map<String, Object> counters;
    }

    /** 서버가 순번과 발생 시각을 붙여 브라우저 상태 WebSocket으로 방송하는 이벤트다. */
    @lombok.Value
    @lombok.AllArgsConstructor
    @lombok.Builder
    @lombok.extern.jackson.Jacksonized
    @lombok.experimental.Accessors(fluent = true)
    @com.fasterxml.jackson.annotation.JsonAutoDetect(
            fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
    public static class BrowserEvent {
        /** 서버 프로세스 안에서 1씩 증가하는 브라우저 이벤트 순번이다. */
        long sequence;

        /** 화면이 Agent/GNSS/TX/RX/세션/결과를 구분하는 이벤트 종류다. */
        EventType type;

        /** 서버가 브라우저 이벤트를 생성한 UTC 시각이다. */
        Instant occurredAt;

        /** 관련 Agent ID이며 서버 자체 세션 이벤트에서는 {@code null}일 수 있다. */
        String agentId;

        /** 관련 Agent 역할이며 역할 무관 이벤트에서는 {@code null}일 수 있다. */
        AgentRole role;

        /** 관련 시험 세션 UUID이며 연결 상태 이벤트에서는 {@code null}일 수 있다. */
        UUID sessionId;

        /** 이벤트 종류별 DTO 또는 Map으로 구성된 화면 전달 데이터다. */
        Object payload;
    }
}
