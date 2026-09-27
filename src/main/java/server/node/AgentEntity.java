package server.node;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import server.common.LnisModels.AgentRole;
import server.common.LnisModels.AgentState;

import java.time.Instant;
import java.util.List;

/**
 * H2에 저장하는 Windows Agent의 연결 상태 스냅샷이다.
 *
 * <p>Heartbeat 또는 WebSocket 연결 이벤트가 들어올 때마다 갱신되며 영구 이력 데이터가 아니다.
 */
@Entity
@Table(name = "agents")
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = Visibility.ANY)
public class AgentEntity {
    /** Agent 실행 시 부여한 고유 문자열이다. 예: {@code sender-1}, {@code receiver-1}. */
    @Id String agentId;

    /** 이 Agent가 송신 PC와 수신 PC 중 어느 역할을 담당하는지 나타낸다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "agent_role")
    AgentRole role;

    /** 서버가 마지막으로 확인한 연결 및 작업 상태다. */
    @Enumerated(EnumType.STRING)
    AgentState state;

    /** 서버가 Hello 또는 Heartbeat를 마지막으로 받은 UTC 시각이다. */
    Instant lastSeen;

    /** Agent 애플리케이션 배포 버전 문자열이다. */
    String version;

    /** Agent가 로딩한 Native AFS Codec의 ABI 버전이며 서버 호환성 확인에 사용한다. */
    int codecAbiVersion;

    /** Agent가 실행되는 운영체제 이름이다. 예: {@code Windows 11}. */
    String os;

    /** Agent JVM의 CPU 아키텍처다. 예: {@code amd64}. */
    String architecture;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_ipv4_addresses", joinColumns = @JoinColumn(name = "agent_id"))
    @Column(name = "address")
    List<String> ipv4Addresses;

    /** 연결 또는 Agent 처리 오류 설명이며, 정상 상태에서는 {@code null}이다. */
    String error;
}
