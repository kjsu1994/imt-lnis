package server.central.capture;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/** 브라우저가 Sender Agent에 GNSS 직렬 수집 시작을 요청할 때 보내는 API 입력값이다. */
@Value
@AllArgsConstructor
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = Visibility.ANY)
public class CaptureRequest {
    /** 실제 COM 포트를 열 Sender Agent의 고유 ID다. */
    @NotBlank(message = "Sender Agent를 선택하세요.")
    String senderAgentId;

    /** Windows에서 확인한 직렬 포트 이름이다. 예: {@code COM3}. */
    @NotBlank(message = "수집에 사용할 COM 포트를 선택하세요.")
    String portName;

    /** GNSS 장비와 통신할 초당 전송 속도다. 허용 범위는 1,200~4,000,000 baud다. */
    @Min(value = 1200, message = "Baud rate는 1200 이상이어야 합니다.")
    @Max(value = 4_000_000, message = "Baud rate는 4000000 이하여야 합니다.")
    int baudRate;

    /** 수신 바이트를 해석할 GNSS 프로토콜 식별자다. 현재 대표 값은 {@code UBX}다. */
    @NotBlank(message = "GNSS 수집 프로토콜을 선택하세요.")
    String protocolId;

    /** 사용자가 시험 화면에서 구분하기 위한 선택적 수집 이름이다. */
    String sessionName;

    /** GRAW 메타데이터에 기록할 GNSS 수신기 모델명이다. */
    String receiverModel;

    /** GRAW 메타데이터에 기록할 수신기 펌웨어 버전이다. */
    String firmwareVersion;

    /** 직렬 포트를 열 때 DTR(Data Terminal Ready) 제어선을 활성화할지 여부다. */
    boolean dtrEnabled;

    /** 직렬 포트를 열 때 RTS(Request To Send) 제어선을 활성화할지 여부다. */
    boolean rtsEnabled;

    /** DTN only: automatically select one epoch with valid terrestrial position and velocity. */
    boolean singleEpoch;
}

