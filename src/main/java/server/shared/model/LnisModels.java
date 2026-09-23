package server.shared.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 서버, Agent 및 다운로드 산출물이 함께 사용하는 시험 도메인 모델 모음이다.
 *
 * <p>이 타입들은 Spring Entity가 아니라 실행 환경에 독립적인 protocol 모델이다. Lombok {@code @Value}로 불변성을 유지해 비동기
 * WebSocket 처리 중 값이 변경되지 않게 하고, collection은 생성자에서 방어적 복사해 외부 변경을 차단한다.
 */
public final class LnisModels {
  private LnisModels() {}

  /** Agent 프로세스가 수행하는 고정 역할이다. */
  public enum AgentRole {
    /** GNSS 입력을 수집하고 DTN 송신 데이터를 준비한다. */
    SENDER,
    /** DTN 수신 데이터를 복원하고 검증한다. */
    RECEIVER
  }

  /** 중앙 서버가 관찰하는 Agent 연결 및 작업 상태다. */
  public enum AgentState {
    /** WebSocket 연결이 없거나 Heartbeat 유효 시간이 지난 상태다. */
    OFFLINE,
    /** Agent가 서버 연결 또는 초기화를 진행 중인 상태다. */
    CONNECTING,
    /** 새 명령을 받을 수 있는 정상 대기 상태다. */
    READY,
    /** GNSS 수집 또는 시험 송수신 명령을 수행 중인 상태다. */
    BUSY,
    /** Agent 처리 오류로 운영자 확인이 필요한 상태다. */
    ERROR
  }

  /** 시험 입력이 생성된 경로를 구분한다. */
  public enum InputKind {
    /** 브라우저가 기존 {@code .graw} 파일을 청크 업로드한 입력이다. */
    GRAW_UPLOAD,
    /** Sender Agent가 COM 포트에서 실시간 GNSS 데이터를 수집해 생성한 입력이다. */
    GNSS_CAPTURE
  }

  /** Receiver가 CRC 정상 SB2에서 해석한 LANS ephemeris와 검증 결과다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class Sb2EphemerisResult {
    String profileId;
    int prn;
    int week;

    /** GPS 주 내 1,200초 구간 번호인 AFS ITOW다. u-blox iTOW(ms)와 다르다. */
    int afsItow;

    int toeSeconds;
    double eccentricity;
    double sqrtSemiMajorAxis;
    double inclinationRadians;
    double ascendingNodeRadians;
    double argumentOfPerigeeRadians;
    double meanAnomalyRadians;
    int tocSeconds;
    double af0Seconds;
    double af1SecondsPerSecond;
    boolean headerMatchesPacket;
    boolean ephemerisMatchesConfigured;
    boolean tailTestPatternValid;
  }

  /** 완료 검증된 입력을 Agent에 전달할 때 사용하는 크기·해시 manifest다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class InputManifest {
    /** Redis 입력 버퍼의 UUID다. */
    UUID inputId;

    /** 파일 업로드 또는 GNSS 실시간 수집 구분값이다. */
    InputKind kind;

    /** 화면과 결과 파일에 표시할 원본 파일 이름이다. */
    String fileName;

    /** 완료된 입력 전체 크기이며 단위는 byte다. */
    long size;

    /** 입력 전체 바이트의 대문자 16진수 SHA-256이다. */
    String sha256;

    /** 구조와 CRC 검사를 통과한 GRAW 레코드 개수다. */
    long recordCount;

    /** Redis에 분할 저장된 입력 청크 개수다. */
    long chunkCount;

    /** 서버가 입력 완료 검증을 확정한 UTC 시각이다. */
    Instant completedAt;
  }

  /** null 또는 공백 문자열만 fallback으로 치환하고 유효한 입력은 그대로 유지한다. */
  public static String blankToDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
