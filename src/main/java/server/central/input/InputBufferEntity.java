package server.central.input;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import server.shared.model.LnisModels.InputKind;

import java.time.Instant;
import java.util.UUID;

/** H2에 저장하는 GRAW 입력 버퍼의 메타데이터와 청크 수신 진행 상태다. */
@Entity
@Table(name = "input_buffers")
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = Visibility.ANY)
public class InputBufferEntity {
    /** 입력 버퍼와 이후 시험 세션이 공유하는 UUID다. */
    @Id
    UUID inputId;

    /** 업로드 파일인지 Agent GNSS 수집 데이터인지 나타낸다. */
    @Enumerated(EnumType.STRING)
    InputKind kind;

    /** 사용자가 제공한 원본 파일 또는 수집 결과의 표시 이름이다. */
    String fileName;

    /** 입력 생성 시 클라이언트가 선언한 예상 전체 크기이며 단위는 byte다. */
    long declaredSize;

    /** GRAW 파일에 실제로 누적 수신된 크기이며 단위는 byte다. */
    long receivedSize;

    /** 지금까지 순서대로 저장한 입력 청크 개수다. */
    long chunkCount;

    /** 완료 검증에서 구조와 CRC가 정상이라고 확인한 GRAW 레코드 개수다. */
    long recordCount;

    /** 완료된 전체 입력 바이트의 대문자 16진수 SHA-256이며 완료 전에는 {@code null}이다. */
    String sha256;

    /** 크기·GRAW 구조·CRC·SHA-256 검증과 완료 처리가 끝났는지 여부다. */
    boolean complete;

    /** 입력 버퍼를 최초 생성한 UTC 시각이다. */
    Instant createdAt;

    /** 완료 검증이 끝난 UTC 시각이며 완료 전에는 {@code null}이다. */
    Instant completedAt;

    /** PVT calculated by the capture Agent, retained with the immutable completed input. */
    @Lob
    @com.fasterxml.jackson.annotation.JsonIgnore
    String capturedPvtJson;

    public InputBufferEntity(UUID inputId, InputKind kind, String fileName, long declaredSize,
            long receivedSize, long chunkCount, long recordCount, String sha256, boolean complete,
            Instant createdAt, Instant completedAt) {
        this(inputId, kind, fileName, declaredSize, receivedSize, chunkCount, recordCount, sha256,
                complete, createdAt, completedAt, null);
    }
}
