# LNIS API 명세서

현재 소스 코드에 구현된 LNIS 송신·수신 독립 노드 및 기존 중앙 서버의 REST API와 WebSocket 계약입니다.

최종 확인: 2026-09-14. **외부 DTN/HDTN 어댑터 개발자에게는 맨 아래 15장 전체를 전달하세요.** 1~14장은 LNIS 내부 개발·운영용입니다.

- 기준 버전: `1.0.0`
- Agent WebSocket protocol: `3`
- 현재 개발 주소: 송신 `http://192.168.219.100:8090`, 수신 `http://192.168.219.100:8091`
- 운영은 송신·수신 각각 다른 PC입니다. 앞쪽의 `192.168.1.72:8088` 예시는 기존 중앙 서버 주소이므로 실제 노드 주소로 바꿉니다.
- REST 기본 경로: `/lnis/api/v1`
- 인코딩: UTF-8
- 시간: ISO-8601 UTC 문자열
- 식별자: UUID

## 1. 공통 규칙

### Content-Type

| 용도 | Content-Type |
|---|---|
| 일반 REST | `application/json` |
| GRAW 청크 | `application/octet-stream` |
| CSV | `text/csv;charset=UTF-8` |
| Excel | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` |

JSON 응답에서는 값이 `null`인 속성이 생략될 수 있습니다.

### 인증

현재 브라우저 REST API에는 사용자 인증이 없습니다. 신뢰할 수 있는 시험 LAN에서만 사용해야 합니다.

Agent WebSocket은 다음 두 헤더로 인증합니다.

```http
X-LNIS-Agent-Id: sender-1
Authorization: Bearer <agent-token>
```

Agent ID와 token은 서버의 `LNIS_AGENT_TOKENS` 설정과 일치해야 합니다.

### HTTP 상태 코드

| 상태 | 의미 |
|---|---|
| `200 OK` | 성공 |
| `204 No Content` | 활성 세션 없음 |
| `400 Bad Request` | 잘못된 값·역할·파일명 또는 존재하지 않는 리소스 |
| `409 Conflict` | Agent 오프라인, 미완료 입력, 중복 시험 또는 상태 충돌 |
| `404 Not Found` | 등록되지 않은 URL |

업무 예외는 RFC 9457 Problem Detail로 반환됩니다.

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Another test session is active",
  "instance": "/lnis/api/v1/sessions",
  "code": "CONFLICT"
}
```

`IllegalArgumentException`과 요청 검증 오류는 `400`, `IllegalStateException`은 `409`입니다.

## 2. API 목록

아래 경로에는 모두 `/lnis/api/v1`을 앞에 붙입니다.

| 구분 | Method | 경로 | 설명 |
|---|---|---|---|
| Discovery | GET | `/discovery` | 중앙 서버 식별 |
| Agent | GET | `/agents` | 전체 Agent 조회 |
| Agent | GET | `/agents/{agentId}` | Agent 조회 |
| Agent | POST | `/agents/{agentId}/serial-ports/refresh` | COM 포트 목록 요청 |
| Input | POST | `/inputs` | GRAW 입력 생성 |
| Input | PUT | `/inputs/{inputId}/chunks/{index}` | GRAW 청크 업로드 |
| Input | POST | `/inputs/{inputId}/complete` | 입력 검증·완료 |
| Input | GET | `/inputs/{inputId}` | 입력 조회 |
| Input | DELETE | `/inputs/{inputId}` | 입력 삭제 |
| Capture | POST | `/captures` | GNSS 수집 시작 |
| Capture | POST | `/captures/{captureId}/stop` | GNSS 수집 중지 |
| Capture | POST | `/captures/{captureId}/complete` | 수집 입력 완료 |
| Session | POST | `/sessions` | AFS 시험 시작 |
| Session | GET | `/sessions/active` | 활성 시험 조회 |
| Session | GET | `/sessions/{sessionId}` | 시험 조회 |
| Session | POST | `/sessions/{sessionId}/cancel` | 시험 취소 |
| Evidence | GET | `/sessions/{sessionId}/frame-evidence` | 프레임 증거 목록 |
| Evidence | GET | `/sessions/{sessionId}/frame-evidence/{frameIndex}` | 프레임 증거 상세 |
| Evidence | GET | `/sessions/{sessionId}/frame-evidence/artifacts/{fileName}` | 프레임 증거 파일 |
| Artifact | GET | `/sessions/{sessionId}/artifacts/{fileName}` | 통합 결과 파일 |
| Artifact | GET | `/sessions/{sessionId}/artifacts/{role}/{fileName}` | 역할별 결과 파일 |
| Actuator | GET | `/actuator/health` | 서버 상태 |
| Actuator | GET | `/actuator/health/liveness` | 생존 상태 |
| Actuator | GET | `/actuator/health/readiness` | 준비 상태 |
| Actuator | GET | `/actuator/info` | 서버 정보 |

## 3. Discovery

```http
GET /lnis/api/v1/discovery
```

```json
{
  "service": "lnis-server",
  "agentWebSocketPath": "/lnis/agent/ws"
}
```

Windows Agent가 LAN에서 중앙 서버 후보를 식별할 때 사용합니다.

## 4. Agent

### 전체 Agent 조회

```http
GET /lnis/api/v1/agents
```

```json
[
  {
    "agentId": "sender-1",
    "role": "SENDER",
    "state": "READY",
    "lastSeen": "2026-09-04T02:17:57.277580Z",
    "version": "1.0.0",
    "codecAbiVersion": 1,
    "os": "Windows 11",
    "architecture": "amd64",
    "ipv4Addresses": ["192.168.1.72"],
    "error": null
  }
]
```

`role`은 `SENDER`, `RECEIVER`이고 `state`는 `OFFLINE`, `CONNECTING`, `READY`, `BUSY`, `ERROR` 중 하나입니다.

### Agent 한 개 조회

```http
GET /lnis/api/v1/agents/{agentId}
```

응답은 전체 조회의 단일 항목과 같습니다.

### COM 포트 새로고침

```http
POST /lnis/api/v1/agents/{agentId}/serial-ports/refresh
```

```json
{
  "commandId": "7f7fd0f1-998b-4b31-b282-a667062340af",
  "accepted": true
}
```

이는 명령 전송 접수만 의미합니다. 실제 포트 목록은 브라우저 WebSocket으로 비동기 전달됩니다.

## 5. GRAW Input

### 입력 생성

```http
POST /lnis/api/v1/inputs
Content-Type: application/json
```

```json
{
  "fileName": "capture.graw",
  "size": 464,
  "kind": "GRAW_UPLOAD"
}
```

| 필드 | 필수 | 설명 |
|---|---|---|
| `fileName` | O | 경로가 아닌 표시 파일명 |
| `size` | O | 예상 byte 크기, 0 이상 |
| `kind` | X | `GRAW_UPLOAD`, `GNSS_CAPTURE`; 기본 `GRAW_UPLOAD` |

### 청크 업로드

```http
PUT /lnis/api/v1/inputs/{inputId}/chunks/{index}
Content-Type: application/octet-stream
```

본문은 Base64나 JSON이 아닌 GRAW 원본 byte입니다.

- 청크 크기: 1~1,048,576 byte
- 첫 index: `0`
- 다음 index는 현재 `chunkCount`와 정확히 같아야 함
- 완료된 입력에는 추가 불가

```powershell
$bytes = [IO.File]::ReadAllBytes('C:\sample\capture.graw')
Invoke-RestMethod `
  -Uri 'http://192.168.1.72:8088/lnis/api/v1/inputs/{inputId}/chunks/0' `
  -Method Put -ContentType 'application/octet-stream' -Body $bytes
```

### 입력 완료

```http
POST /lnis/api/v1/inputs/{inputId}/complete
```

모든 청크, 선언 크기, length-prefixed GRAW record 구조, CRC, 잘림 여부와 전체 SHA-256을 검증합니다.

### 입력 조회

```http
GET /lnis/api/v1/inputs/{inputId}
```

| 필드 | 설명 |
|---|---|
| `inputId` | 입력 UUID |
| `kind` | 입력 종류 |
| `fileName` | 표시 파일명 |
| `declaredSize`, `receivedSize` | 선언·수신 byte |
| `chunkCount`, `recordCount` | 청크·GRAW record 수 |
| `sha256` | 완료 입력 SHA-256 |
| `complete` | 완료 여부 |
| `createdAt`, `completedAt` | 생성·완료 시각 |

### 입력 삭제

```http
DELETE /lnis/api/v1/inputs/{inputId}
```

```json
{"removed": true}
```

명시적 삭제는 세션 참조 여부와 관계없이 메타데이터와 파일을 제거하므로 주의합니다.

## 5.1 화면용 어댑터 상태 조회

어댑터 외부 계약은 문서 마지막 **15장**에 통합했습니다.
화면은 `GET /lnis/api/v1/dtn/adapter-health?adapterUrl=...`로 LNIS에 조회를 요청합니다. 어댑터가 구현할 경로는 아닙니다.

## 6. GNSS Capture

### 수집 시작

```http
POST /lnis/api/v1/captures
Content-Type: application/json
```

```json
{
  "senderAgentId": "sender-1",
  "portName": "COM3",
  "baudRate": 115200,
  "protocolId": "ubx",
  "sessionName": "현장 수집 1",
  "receiverModel": "u-blox EVK-F9T",
  "firmwareVersion": "",
  "dtrEnabled": false,
  "rtsEnabled": false
}
```

- `senderAgentId`, `portName`, `protocolId`: 필수
- `baudRate`: 1,200~4,000,000
- protocol 대표 값: `ubx`, `lnis-canonical-v1`, `raw-only`

성공 시 `kind=GNSS_CAPTURE`, `complete=false`인 Input 객체를 반환합니다. Agent 명령 전송 실패 시 생성한 입력을 보상 삭제합니다.

### 수집 중지

```http
POST /lnis/api/v1/captures/{captureId}/stop?senderAgentId=sender-1
```

```json
{"commandId": "98bab072-3ea1-46d3-bf07-42a71bfd0f12", "accepted": true}
```

### 수집 입력 완료

```http
POST /lnis/api/v1/captures/{captureId}/complete
```

canonical GRAW 검증 후 완료된 Input 객체를 반환합니다. `raw-only` 입력은 시험 입력으로 완료할 수 없습니다.

## 7. Session

### 시험 생성

```http
POST /lnis/api/v1/sessions
Content-Type: application/json
```

```json
{
  "senderAgentId": "sender-1",
  "receiverAgentId": "receiver-1",
  "inputId": "4c0694f1-ce13-4a03-90d1-94288775f7bd",
  "afs": {"prn": 1},
  "options": {
    "testType": "TEST_A_NORMAL",
    "errorCount": 1,
    "errorSeed": 1,
    "syncDamageInterval": 10,
    "thresholds": {}
  }
}
```

시험 종류:

- `TEST_A_NORMAL`: 정상 송수신
- `TEST_B_RANDOM_ERRORS`: 임의 비트 오류
- `TEST_C_BURST_ERRORS`: 연속 비트 오류
- `TEST_D_SYNC_RECOVERY`: 동기 손상 후 재동기

검증 조건:

- 입력은 `complete=true`
- Agent가 존재하고 Sender/Receiver 역할이 일치
- 동시에 하나의 활성 시험만 허용
- `afs.prn`: 1~8
- Test B/C `errorCount`: 1~5,880
- Test D `errorCount`: 1~68, `syncDamageInterval`: 1 이상

| 0 또는 생략 시 기본값 | 값 |
|---|---|
| `afs.prn` | `1` |
| `testType` | `TEST_A_NORMAL` |
| `errorCount`, `errorSeed` | `1` |
| `syncDamageInterval` | `10` |

처리 순서:

```text
검증 → H2 lock → WAITING_RECEIVER 저장 → Receiver ARM
     → Sender GRAW 전달 → Sender START
```

중간 실패 시 양쪽 Agent에 `CANCEL_SESSION`을 시도하고 세션을 `FAILED`로 저장한 후 lock을 해제합니다.

### 활성 시험

```http
GET /lnis/api/v1/sessions/active
```

활성 시험이 있으면 `200`과 SessionSnapshot, 없으면 `204`입니다.

### 시험 조회

```http
GET /lnis/api/v1/sessions/{sessionId}
```

| 필드 | 설명 |
|---|---|
| `sessionId` | 시험 UUID |
| `state` | 실행 상태 |
| `testType` | Test 종류 |
| `senderAgentId`, `receiverAgentId` | 참여 Agent |
| `inputId` | 입력 UUID |
| `progress` | 0~100 |
| `message` | 단계 또는 종료 사유 |
| `verdict` | `PASS`, `FAIL`, `INCONCLUSIVE` |
| `createdAt`, `updatedAt` | 생성·갱신 시각 |
| `txResult`, `rxResult` | 역할별 결과, 미도착 시 생략 |

상태 값:

`CREATED`, `WAITING_RECEIVER`, `TRANSMITTING`, `EVALUATING`, `COMPLETED`, `CANCELLED`, `FAILED`, `INCONCLUSIVE`

### 시험 취소

```http
POST /lnis/api/v1/sessions/{sessionId}/cancel
```

양쪽 Agent에 취소를 각각 시도하고 중앙 상태를 `CANCELLED`로 저장합니다. 한 Agent가 오프라인이어도 나머지 취소와 lock 해제를 계속합니다.

## 8. 결과 산출물

### 통합 결과

```http
GET /lnis/api/v1/sessions/{sessionId}/artifacts/lnis-report.json
GET /lnis/api/v1/sessions/{sessionId}/artifacts/lnis-report.xlsx
```

통합 JSON 최상위 필드:

```json
{
  "schemaVersion": 1,
  "sessionId": "...",
  "generatedAt": "...",
  "senderResult": {},
  "receiverResult": {},
  "frameEvidence": []
}
```

한 역할 결과만 있어도 생성되지만 양쪽 결과가 모두 없으면 `400`입니다.

### 역할별 결과

```http
GET /lnis/api/v1/sessions/{sessionId}/artifacts/{role}/{fileName}
```

- Sender role: `tx`, `sender`
- Receiver role: `rx`, `receiver`
- 파일: `result.json`, `metrics-summary.csv`, `metrics-timeseries.csv`

RoleResult에는 `schemaVersion`, `sessionId`, `role`, `verdict`, `completedAt`, `integrity`, `metrics`, `counters`, `samples`, `error`가 포함됩니다.

## 9. Frame Evidence

### 목록

```http
GET /lnis/api/v1/sessions/{sessionId}/frame-evidence
```

프레임 번호는 `0`부터 시작합니다. 주요 필드는 다음과 같습니다.

- 증거: `senderEvidenceAvailable`, `receiverEvidenceAvailable`
- Decoder/CRC: `decoderCompleted`, `decodeSucceeded`, `sb2CrcValid`, `sb3CrcValid`, `sb4CrcValid`
- 판정 변경량: `sb2DecisionChanges`, `sb3DecisionChanges`, `sb4DecisionChanges`
- 해시: `referenceSha256`, `transmittedSha256`, `receivedSha256`, `reencodedSha256`
- 차이 수: `referenceToTransmittedDifferences`, `transmittedToReceivedDifferences`, `referenceToReencodedDifferences`
- 진단: `injectedBitPositions`, `intentionalSyncRejection`, `failureReason`, `interpretation`, `sb2Ephemeris`

### 상세

```http
GET /lnis/api/v1/sessions/{sessionId}/frame-evidence/{frameIndex}
```

```json
{
  "summary": {},
  "referenceFrame": "<Base64>",
  "transmittedFrame": "<Base64>",
  "receivedFrame": "<Base64>",
  "reencodedFrame": "<Base64>",
  "referenceToTransmittedPositions": [123],
  "transmittedToReceivedPositions": [],
  "referenceToReencodedPositions": []
}
```

각 프레임 원문은 750 byte이며 JSON에서는 Base64입니다.

### 다운로드

```http
GET /lnis/api/v1/sessions/{sessionId}/frame-evidence/artifacts/frame-evidence.json
GET /lnis/api/v1/sessions/{sessionId}/frame-evidence/artifacts/frame-diff-summary.csv
```

## 10. WebSocket

### 브라우저 상태

```text
ws://192.168.1.72:8088/lnis/ws/status
```

브라우저는 서버가 방송하는 이벤트를 구독하며 별도 인증은 없습니다.

```json
{
  "sequence": 101,
  "type": "SESSION_STATUS",
  "occurredAt": "2026-09-04T02:17:57.277580Z",
  "agentId": "sender-1",
  "role": "SENDER",
  "sessionId": "bf17461d-4d05-4e71-8944-8f409d96031c",
  "payload": {}
}
```

이벤트 종류:

`AGENT_STATUS`, `GNSS_STATUS`, `TX_STATUS`, `RX_STATUS`, `SESSION_STATUS`, `RESULT`, `ERROR`

WebSocket은 실시간 표시용입니다. 재접속 기준 상태는 `/agents`, `/sessions/active`, `/sessions/{sessionId}`로 복원합니다.

### Agent 제어

```text
ws://192.168.1.72:8088/lnis/agent/ws
```

Handshake에는 `X-LNIS-Agent-Id`와 `Authorization: Bearer ...`가 필요합니다.

```json
{
  "protocolVersion": 3,
  "type": "HEARTBEAT",
  "messageId": "e933e096-3ddf-45a7-a27e-a30a30d829ee",
  "correlationId": null,
  "agentId": "receiver-1",
  "role": "RECEIVER",
  "sessionId": null,
  "occurredAt": "2026-09-04T02:17:57.277580Z",
  "payload": {}
}
```

메시지 종류:

`HELLO`, `HELLO_ACK`, `HEARTBEAT`, `COMMAND`, `COMMAND_ACK`, `STATUS`, `PORT_LIST`, `INPUT_CHUNK`, `INPUT_COMPLETE`, `FRAME_EVIDENCE`, `ROLE_RESULT`, `ERROR`

명령 종류:

`LIST_PORTS`, `START_CAPTURE`, `STOP_CAPTURE`, `ARM_RECEIVER`, `START_SENDER`, `CANCEL_SESSION`

## 11. 화면 경로

아래는 REST API가 아니라 HTML 화면입니다.

| 경로 | 설명 |
|---|---|
| `/` | AFS Sender로 redirect |
| `/lnis/afstest/sender` | AFS Sender |
| `/lnis/afstest/receiver` | AFS Receiver |
| `/lnis/test/sender` | 기존 Sender 호환 주소 |
| `/lnis/test/receiver` | 기존 Receiver 호환 주소 |
| `/lnis/dtntest/sender` | DTN 송수신 및 PVT 비교 화면 |
| `/lnis/dtntest/receiver` | 수신 데이터·독립 계산 PVT·읽기 전용 시험 설정 |

## 12. 일반 사용 순서

GRAW 파일 시험:

```text
GET  /agents
POST /inputs
PUT  /inputs/{id}/chunks/0...N
POST /inputs/{id}/complete
POST /sessions
GET  /sessions/{id}
GET  /sessions/{id}/artifacts/...
```

GNSS 수집 후 시험:

```text
POST /agents/{sender}/serial-ports/refresh
POST /captures
POST /captures/{id}/stop
POST /captures/{id}/complete
POST /sessions
```

## 13. DTN 화면·시험 제어 API — LNIS 내부용

### 13.1 화면용 제어 API

아래는 어댑터가 호출할 API가 아닙니다.

- 시험 생성에는 `testType`, `senderMode`, `receiverMode`를 전달합니다. RAW/AFS는 `inputId`, I/Q는 `iqFileId`를 사용합니다.
- 기존 호출 호환: 시험 유형 생략은 AFS_METADATA, 경로 생략은 DTN→HDTN입니다. 새 화면은 항상 명시합니다.
- `POST /lnis/api/v1/dtn/iq`: `{"inputId":"완료된 GRAW 입력 UUID"}`로 90초 I/Q 생성 시작. 유효한 지구 위치·속도와 관측 GPS PRN의 LNAV가 필요합니다. 입력 없는 기본 달 시나리오 생성은 허용하지 않습니다.
- `GET /lnis/api/v1/dtn/iq`, `GET /lnis/api/v1/dtn/iq/{id}`: 생성 상태·완료 파일·미리보기 조회.
- `POST /lnis/api/v1/dtn/iq/{id}/cancel`: 생성 취소.
- `DELETE /lnis/api/v1/dtn/iq/{id}`: 해당 로컬 BIN·메타데이터 삭제. 생성/전송/검증 중 삭제 거부, 시험 이력 유지.
- 개발 옵션에서만 `POST /lnis/api/v1/dtn/example/replay`, `GET /lnis/api/v1/dtn/example/synthetic/file` 제공. 실제 COM 수집이나 F9T 실측이 아닙니다.

- `GET /lnis/api/v1/dtn/config`: 외부 연동 설정 여부, 계산 프로파일, 입력 크기 상한.
- 수집 시작: 기존 `POST /lnis/api/v1/captures` 사용.
- `POST /lnis/api/v1/dtn/captures/{id}/stop?senderAgentId=sender-1`: 마지막 청크 전달까지 기다리는 수집 종료 명령.
- 브라우저는 해당 수집 ID의 GNSS_STATUS/Stopped 이벤트 확인 후 기존 `POST /captures/{id}/complete`로 확정한다.
- `POST /lnis/api/v1/dtn/tests`: 수집 완료 입력으로 기준 계산, AFS 생성 및 외부 전송을 시작한다.
- `POST /lnis/api/v1/dtn/tests/{id}/cancel`: 송신 화면에서 시험을 중지한다. 응답은 시험 요약이며 `CANCELLED` 상태와 `cancelPending`(상대 노드 중지 확인 대기 여부)을 포함한다. 진행 중 또는 송신 실패 후 남은 수신 대기를 정리할 수 있다. 완료된 시험 결과는 변경하지 않는다.
- `GET /lnis/api/v1/dtn/tests/{id}`: 상태 요약.
- `GET /lnis/api/v1/dtn/tests/{id}/report`: 기준/수신 PVT, 관측 시각별 비교 JSON.
- `GET /lnis/api/v1/dtn/inputs/{id}/observations`: 완료 GRAW의 `epochs`, `navigationCount`, `receiver`, `navigation`, `records`. 항법 메시지는 수집 순서·중복을 보존합니다. `navigation`의 항목은 `sequence`, `capturedAt`, `message`이며 `records`는 저장된 메시지 종류와 해석 필드 전체를 제공합니다. 외부 전송 JSON에 이 화면용 객체를 추가하지 않습니다.
- `GET /lnis/api/v1/dtn/inputs/{id}/pvt`: 입력의 지구 PVT 배열. 수신기의 NAV-PVT 출력이 아닌 프로그램의 재계산 결과입니다.
- 현재 GRAW는 RAWX·SFRBX·수집 메타데이터를 보존합니다. NAV-PVT·NMEA 등 모든 수신기 출력의 원문 기록은 아닙니다.
- 합성 재생 버튼은 HTML `#dtn-development[hidden]`으로 기본 숨김입니다. 개발자 도구에서 숨김을 해제할 수 있으나 서버의 개발 예제 옵션도 활성화되어 있어야 합니다. F9T 예제 버튼과 합성 다운로드 링크는 화면에서 제거했습니다.

시험 시작 요청:

```json
{
  "inputId": "b191cc34-1f80-4b7f-8644-822d3d014d8a",
  "senderAgentId": "sender-1",
  "receiverAgentId": "receiver-1"
}
```

시험 상태:
`PREPARING → WAITING_DTN → WAITING_RECEIVER → CALCULATING → COMPLETED/INCONCLUSIVE`.
각 단계 실패는 FAILED로 기록한다. 시험 전체 제한 시간은 10분이다.
동시에 하나의 DTN 시험만 허용한다.

#### 시험별 DTN/HDTN 어댑터 URL 지정

`POST /lnis/api/v1/dtn/tests` 요청에 선택 필드 `sendUrl`을 추가한다.

```json
{
  "inputId": "00000000-0000-0000-0000-000000000001",
  "senderAgentId": "sender-1",
  "receiverAgentId": "receiver-1",
  "sendUrl": "http://192.168.1.100:8080"
}
```

- 생략하거나 공백이면 `dtn_adapter`을 사용한다. 기본 서버 주소에는 `/transfers`를 자동 적용하며 기존 경로 포함 URL도 지원한다.
- 양쪽 PC의 화면에는 각자 설정한 `dtn_adapter`를 기본값으로 채운다. 수신 화면 주소는 로컬 어댑터 헬스체크에 사용한다.
- 화면에서 지정한 URL은 시험 생성 시 DB에 저장하며, 이후 입력란 변경은 진행 중 시험에 영향을 주지 않는다.
- 서버가 해당 URL로 `POST`, `Content-Type: application/json` 요청을 보낸다. 브라우저에서 어댑터를 직접 호출하지 않는다.
- `http`/`https`와 유효한 호스트를 요구한다. 최대 2048자이며 URL 내부 인증 정보, fragment, 잘못된 포트는 거부한다.
- 다른 주소로의 redirect는 따라가지 않는다.
- `LNIS_DTN_SEND_TOKEN`은 정규화된 전체 URL이 기본 설정과 일치할 때만 사용한다. 다른 URL에 비밀 토큰을 자동 전달하지 않는다.
- 별도 URL의 인증 헤더 편집 기능은 제공하지 않는다. 인증이 필요한 어댑터는 운영 기본 URL/토큰 설정을 사용한다.
- `localhost`는 LNIS 서버/컨테이너 기준이다. 접근이 제한된 시험망에서 사용하고 관리 화면/API를 공개망에 노출하지 않는다.
- 시험 요약/보고서 응답의 `sendUrl`로 실제 선택한 대상을 확인한다. 과거 시험에는 이 값이 없을 수 있다.
- `/dtn/config`에 `defaultSendUrl`, `receiveConfigured`, `defaultSendTokenConfigured`를 추가한다. 토큰 자체는 반환하지 않는다.

### 내부 처리 로그

내부 처리 로그: `GET /lnis/api/v1/dtn/logs?scopeId={UUID}&after=0`.
`scopeId`는 입력·I/Q 작업·시험 ID이며, 응답은 `entries`, `nextSequence`, `hasMore`입니다(페이지당 최대 500건).
각 항목은 `sequence`, `scopeId`, `scopeType`, `occurredAt`(UTC), `level`, `stage`, `detail`, `message`를 포함합니다.
`download=true`는 해당 작업의 전체 상세 로그를 UTF-8 TXT로 반환합니다. 화면은 PC의 현지 시각으로 표시합니다.
DTN 파일 업로드는 `POST /lnis/api/v1/inputs?dtn=true`로 로그 기록을 시작합니다. 시험 생성 시 준비 로그를 복사하며 각 PC의 로그만 저장합니다.
시험 로그는 보존하고, 시험에 연결하지 않은 준비 로그는 7일 후 정리합니다. 화면 지우기는 서버 기록을 삭제하지 않습니다. 어댑터 요청 JSON에는 로그를 추가하지 않습니다.

### 13.2 PVT 의미와 판정


현재 계산 프로파일은 GPS L1 C/A 단독 측위다. 다중 GNSS 전체 지원을 의미하지 않는다.
PocketSDR-AFS 일반 GNSS 경로와 같은 RTKLIB pntpos(), 고도각 15도,
방송 전리층 모델, Saastamoinen 대류권 보정을 사용한다.
관측 데이터 순서대로 항법정보를 갱신하며, DTN 도착 시각으로 관측 시각을 대체하지 않는다.

P는 지구 중심 지구 고정 좌표(ECEF), 단위 m.
V는 ECEF 속도, 단위 m/s. T 비교 항목은 수신기 시계 오차, 단위 s.
관측 시각은 GPS week와 TOW(s)로 별도 제공한다.
계산 실패는 positionValid=false이며, 속도 해가 없으면 velocityValid=false다.
이때 해당 좌표/속도는 null이며 정상적인 0으로 표시하지 않는다.

동일 관측 시각에 대해 위치 차이 0.001 m 이하, 속도 차이 0.001 m/s 이하,
시계 오차 차이 1e-9 s 이하를 일치로 판정한다.
양쪽 유효성 차이나 임계값 초과는 FAIL.
비교 가능한 위치 또는 속도 해가 없으면 INCONCLUSIVE.
이 판정은 전달 전후 계산 일치성이지 절대 위치 정확도 인증이 아니다.

### 13.3 운영 설정

화면의 어댑터 주소 `저장·적용`은 해당 브라우저·역할에 저장한다. 새로고침 시 복원하며 서버 `.env`와 인증 토큰은 변경하지 않는다.

운영 폴더 `.env`의 어댑터 주소·인증 설정은 [문서 하단 연동 계약](#adapter-contract)을 따른다. 변경 후 서버를 재시작한다.
운영 `node` 모드의 콜백 대상은 수신 PC의 LNIS이다. 레거시 `server` 모드는 중앙 서버가 접수한다.

### DTN 역할별 화면 및 최근 시험 조회

- Sender 화면: `/lnis/dtntest/sender` — 수집 및 전송 제어
- Receiver 화면: `/lnis/dtntest/receiver` — Agent 상태, 수신 및 PVT 비교 결과 조회
- `GET /lnis/api/v1/dtn/tests`: 생성 시각 내림차순 최근 50개 시험 요약 배열.
  개별 시험 조회와 같은 필드에 `senderAgentId`, `receiverAgentId`를 포함한다.
  원본 프레임과 전체 PVT는 포함하지 않는다. 상세 결과는 기존 report API를 사용한다.
- Receiver 화면은 2초마다 조회하며 수집·전송·계산 명령을 실행하지 않는다.
  별도 PC에서도 중앙 서버에 저장된 시험을 조회할 수 있다.

### DTN 송신·수신 JSON 본문 조회 및 다운로드

`GET /lnis/api/v1/dtn/tests/{testId}/payload/{direction}?download=false`

- `direction`: `sent` 또는 `received`. 다른 값은 `400`.
- 정상 응답: `200`, `Content-Type: application/json;charset=UTF-8`. 별도 응답 객체로 감싸지 않은 JSON 본문이다.
- `download=true`: 같은 본문을 `dtn-{testId}-{direction}.json` 첨부 파일로 반환한다.
- `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`를 적용한다.
- `X-LNIS-Payload-Representation`: `original` 또는 `legacy-normalized`.
- 아직 본문이 준비되지 않았으면 `409`. 시험이 존재하지 않으면 기존 시험 조회와 같은 오류를 반환한다.

송신 본문은 외부 DTN/HDTN에 전달할 준비된 요청 JSON이다. 본문이 있다는 사실만으로 외부 전송 성공을 의미하지 않는다.
수신 원문은 인증·동일성 검증을 통과해 최초 접수된 UTF-8 본문을 공백과 줄바꿈까지 보존한다.
중복 callback은 최초 원문을 덮어쓰지 않는다. 인증 실패나 검증 거절 본문은 이 조회 API에 보관하지 않는다.
변경 이전 시험은 정규화된 `receivedJson`만 남아 있을 수 있으며, 이 경우 `legacy-normalized`로 표시한다.
요청의 인증 헤더·토큰은 본문 조회에 포함하지 않는다.

시험 요약에는 다음 필드가 추가된다.

| 필드 | 의미 |
| --- | --- |
| `sentPayloadAvailable` | 송신 요청 JSON 준비 여부 |
| `receivedPayloadAvailable` | 수신 저장본 조회 가능 여부 |
| `receivedOriginalAvailable` | 공백·줄바꿈까지 보존한 수신 원문 존재 여부 |

송신 화면에는 송신 원문, 수신 화면에는 수신 원문을 제공한다. 본문 준비 시 기본 펼침·정렬 보기 체크 상태이며, 수동으로 접으면 같은 시험의 자동 갱신에서는 다시 열지 않는다. 새 시험은 다시 자동 표시한다.
정렬은 브라우저 표시에만 적용하며 다운로드 파일과 저장된 원문은 변경하지 않는다.

## 14. 독립 노드 관리 API

### 화면에서 수신 노드 연결 설정

JSON 접수 후 수신 복원·PVT 계산은 자동 수행된다. 새로고침은 재계산을 요청하지 않는다. COMPLETED는 처리 완료이며 최종 PASS와 구분한다. 수신 화면은 같은 시각의 기준·수신 PVT를 좌우로 표시한다. GNSS RAW·AFS는 기존 일치 판정, I/Q는 허용오차 미설정 상태의 오차 측정(MEASURED)을 표시한다. I/Q 추적 관측값과 보조 LNAV도 표로 표시하되 수신기 RAWX·SFRBX 원본과 구분한다. 메타데이터 없는 과거 I/Q 파일은 파일 검증만 수행한다.

독립 송신 노드의 AFS/DTN 화면에서 공통으로 사용한다. 관리 토큰은 서버 설정을 사용하며 요청·응답에 토큰 값을 넣지 않는다.

DTN 화면은 기존 수신측 IP·Port 옆에 연결 테스트와 저장·적용을 배치한다. 이 값은 수신 LNIS 관리 주소이며, 바로 아래의 DTN/HDTN 전송 URL과 별개다. 전송 URL은 전체 주소(HTTPS·경로·쿼리 포함)를 그대로 시험 생성 요청의 sendUrl로 전달한다. 연결 상태는 저장된 수신 노드 기준이고, 후보 주소의 테스트 결과는 버튼 아래에 별도로 표시한다.

- `GET /lnis/api/v1/node/connection`: 현재 `ip`, `port`, `scheme`, `baseUrl`, `peerAgentId`, `tokenConfigured`, `editable`, `busy` 반환.
- `POST /lnis/api/v1/node/connection/test`: `{"ip":"192.168.1.73","port":8088}`. 서버가 후보 수신 노드에 인증된 GET 상태 요청을 보낸다. `connected`, `ready`, `elapsedMilliseconds`, `message`, 정상 조회 시 `node` 반환. 현재 주소는 변경하지 않는다.
- `PUT /lnis/api/v1/node/connection`: 같은 본문으로 연결을 다시 확인하고 READY인 경우 H2에 저장·적용한다. AFS/DTN 시험 진행 중이거나 연결 검증 실패 시 기존 설정을 유지한다. `scheme`은 생략 시 `http`이며 기존 HTTPS 설정도 지원한다.

화면 저장값은 환경 변수 `LNIS_NODE_PEER_URL`보다 우선하며 재시작 후 유지된다. IPv4/포트만 입력하며 URL 경로·호스트명·미지정/멀티캐스트/링크 로컬 주소는 거부한다. 잘못된 입력은 `400`, 시험 중 변경 등 상태 오류는 `409`다. 연결 테스트의 접속/인증 오류는 `200`과 `connected=false`로 표시한다. 이 검사는 관리 REST 연결 검사이며 외부 DTN 전달은 검증하지 않는다.

`node` 실행 모드에서만 활성화된다. 기존 `server`, `sender`, `receiver` 실행 계약은 유지한다.
로컬 실행기, 원격 AFS 준비·취소·결과 조회 및 DTN 수신 DB 분리를 지원한다.
Linux 독립 노드 Compose는 `deployment/node`에 있으며 기존 중앙 서버용 Compose와 분리한다.

### 14.1 설정

| 환경 변수 | 의미 |
| --- | --- |
| `LNIS_NODE_ROLE` | 시작 시 고정하는 `sender` 또는 `receiver` 역할 |
| `LNIS_AGENT_ID` | 로컬 실행기 ID. 기본 `sender-1` 또는 `receiver-1` |
| `LNIS_NATIVE_DIR` | 운영체제별 DLL/SO가 있는 디렉터리 |
| `LNIS_NODE_BASE_URL` | 자신의 경로 없는 `http(s)://호스트:포트` 주소 |
| `LNIS_NODE_PEER_URL` | 상대 노드의 고정 기본 주소 |
| `LNIS_NODE_PEER_ID` | 상대 ID. 기본은 반대 역할 ID |
| `LNIS_NODE_MANAGEMENT_TOKEN` | 양쪽이 공유하는 관리 전용 Bearer 토큰. 외부 DTN 토큰과 별도 |

주소에는 사용자 정보, 경로, query 또는 fragment를 지정할 수 없다.
상대 주소나 관리 토큰이 없으면 원격 상태 조회를 거부한다. HTTP redirect는 따르지 않는다.

### 14.2 상태 조회

- `GET /lnis/api/v1/node`: 로컬 화면용 상태 조회.
- `GET /lnis/api/v1/node/peer/status`: 상대 노드용 조회. `Authorization: Bearer <관리 토큰>` 필수이며 누락·불일치·미설정은 `401`.

정상 응답은 `200 OK`이며 다음과 같다.

```json
{
  "protocolVersion": 1,
  "agentId": "sender-1",
  "role": "SENDER",
  "state": "READY",
  "online": true,
  "codecAbiVersion": 1,
  "baseUrl": "http://192.168.1.72:8088"
}
```

`online`은 실제 실행기 등록 여부다. 저장된 과거 상태가 READY여도 `online=false`이면 실행 가능 상태가 아니다.
클라이언트는 상대 ID, 반대 역할, 관리 프로토콜 버전을 검증한다.
상태 응답에는 토큰, 관측 원본, AFS 프레임, 기준 PVT를 포함하지 않는다.

### 14.3 AFS 원격 준비·취소 및 결과

아래 모든 API는 `Authorization: Bearer <관리 토큰>`이 필요하다. 송신/수신 ID는 시작 시 지정한 상대와 일치해야 한다.

- `POST /lnis/api/v1/node/peer/afs/commands`: 기존 Agent protocol v3 `COMMAND` envelope 사용. 수신 노드의 `ARM_RECEIVER`, `CANCEL_SESSION`만 허용한다. 최대 32 KiB. 응답 `200`은 SessionSnapshot이다.
- ARM 인수는 기존 CreateSessionRequest와 같지만 입력 파일을 전송하지 않는다. 수신 PC의 DB/활성 잠금 저장 후 로컬 AFS 수신 세션을 준비한다.
- 같은 시험 ID와 동일 설정의 ARM 재호출은 수신 세션을 중복 생성하지 않는다. 다른 설정 또는 종료된 시험 ID는 `409`.
- 존재하지 않는 시험 취소는 `404`, 종료된 시험 취소는 기존 결과를 반환한다. 부분 준비 실패는 취소·DB 상태 기록·잠금 해제를 수행한다.
- `GET /lnis/api/v1/node/peer/afs/sessions/{id}`: 수신 SessionSnapshot. 수신 PC에서 먼저 자체 결과를 완료하고 송신 PC가 TX/RX 종합 판정을 수행한다.
- `GET /lnis/api/v1/node/peer/afs/sessions/{id}/evidence?after=-1`: `frameIndex > after`인 Receiver 프레임 증거를 오름차순 최대 32건 반환한다. 빈 배열이면 끝이다. AFS 분석 증거 전용이며 DTN 본문은 제공하지 않는다.

입력 청크, START_SENDER, DTN_PROCESS는 노드 관리 채널에서 거부한다. AFS 프레임은 `AFS_TRANSFER_START`, `AFS_TRANSFER_BATCH`, `AFS_TRANSFER_COMPLETE` envelope로 기존 인증된 관리 연결을 통해 전송한다.

### 14.4 DTN 사전 등록

`POST /lnis/api/v1/node/peer/dtn/tests` — 관리 토큰 필수, 수신 노드 전용, 최대 8 KiB.

```json
{
  "testId": "2b23c8bb-f002-4b83-a8e1-8ec7fe1eeb59",
  "senderAgentId": "sender-1",
  "receiverAgentId": "receiver-1",
  "profile": "POCKETSDR-GPS-L1CA-SPP-v1",
  "payloadSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
}
```

`payloadSha256`는 JSON 객체 필드를 이름순으로 재귀 정렬한 뒤 compact JSON UTF-8을 SHA-256 처리한 소문자 64자리 값이다. 배열 순서 및 값은 유지한다. 실제 전송 원문은 정렬하거나 바꾸지 않는다.

응답은 `202`와 14.5의 상태 객체다. 같은 ID/해시/참여자의 재등록은 최초 상태를 유지하며, 변경된 등록은 `409`다. 등록 단계에서는 송신 JSON, 입력 ID/파일, 기준 PVT를 전달하지 않는다. 이후 어댑터 callback 원문에는 `referencePvt`가 포함되며 수신 JSON과 함께 보관한다.

송신 노드는 사전 등록 성공 후에만 외부 DTN/HDTN URL로 기존 Transfer JSON을 POST한다. 외부 wire schema는 변경하지 않는다. 외부 수신 callback은 기존 `/lnis/api/v1/dtn/receive`를 **수신 PC**에서 호출한다. 외부 callback 토큰과 관리 토큰은 별개다.

### 14.5 DTN 수신 결과 조회

`GET /lnis/api/v1/node/peer/dtn/tests/{testId}` — 관리 토큰 필수, 수신 노드 전용.

```json
{
  "testId": "2b23c8bb-f002-4b83-a8e1-8ec7fe1eeb59",
  "state": "WAITING_DTN",
  "message": "외부 DTN/HDTN 수신 대기"
}
```

접수 후 `receivedAt`(UTC), 계산 완료 후 `pvt`(기존 Pvt 배열)가 추가된다. I/Q는 `fileResult`도 반환하며, 보조 항법정보가 있는 신규 시험은 추적 기반 `pvt`를 포함한다. `state=COMPLETED`는 **수신 처리 완료**이며 PVT 일치 판정이 아니다. 송신 PC가 결과를 받아 비교 판정을 저장한다. 어댑터 부가 로그는 `adapterLogs` 배열(`occurredAt`, `level`, `message`)로 공유하며 LNIS 자체 처리 로그·원본 JSON·AFS 프레임은 포함하지 않는다.

송신 화면의 `dtnReceived=true`는 원격 수신 접수를 뜻하며 송신 DB에 수신 원문이 있다는 뜻이 아니다. `sentPayloadAvailable`/`receivedPayloadAvailable`은 **현재 PC의 DB**를 기준으로 한다. 반대쪽 원문은 Sender/Receiver 버튼으로 해당 PC로 이동해 확인한다.

### 14.6 재시작 및 연결 실패

관리 요청은 설정된 상대 주소를 사용하며 리다이렉트를 따르지 않는다. 시험 데이터는 자동 재송신하지 않고, 연결 실패 중에는 다음 상태 조회를 기다린다. 중지 요청은 예외로 `cancelPending=true`인 동안 재시도한다. 재시작으로 사라진 AFS 실행은 취소하고, 메모리에서 진행하던 DTN PREPARING/CALCULATING은 FAILED로 기록한다. 영속 저장된 WAITING_DTN/WAITING_RECEIVER는 기존 접수 대기를 계속한다. 제한 시간은 각 노드의 시험 생성·등록 시각 기준 RAW/AFS 10분, I/Q 20분이다. 수신 노드는 JSON 접수 전에는 시험 종류가 아직 저장되지 않아 기본 10분 제한을 적용한다.

역할 선택 화면은 상대 노드 URL로 이동한다. 수신 PC에서 수집/업로드/전송 시작 API를 호출하면 `409`로 거부한다. 기존 `server` 모드의 화면 및 API 동작은 유지한다.

### 14.7 DTN 상대 시험 중지

`POST /lnis/api/v1/node/peer/dtn/tests/{testId}/cancel` — 관리 토큰 필수, 수신 노드 전용. 본문은 `{}`, 응답은 HTTP 200과 14.5의 상태 객체다. 등록 전 중지도 같은 ID로 기록해 늦은 등록을 차단한다. 완료된 `COMPLETED`/`INCONCLUSIVE` 결과는 유지한다. 어댑터가 호출할 API가 아니다.

송신 화면의 `POST /lnis/api/v1/dtn/tests/{id}/cancel` 응답은 로컬 시험 요약이다. `cancelPending`은 상대 중지 확인 대기 여부이며 외부 어댑터 번들 취소 여부가 아니다. 시험 요약에는 확정된 `hdtnConfig`와 수신 접수 시각 `receivedAt`도 포함된다.

---

<a id="adapter-contract"></a>

## 15. DTN/HDTN 어댑터

### 구성과 담당 범위

```text
송신 PC: LNIS Sender → 송신 어댑터 → DTN/HDTN
                                    ↓ 전송
수신 PC: LNIS Receiver ← 수신 어댑터 ← DTN/HDTN
```

### 15.1 구현할 엔드포인트와 Health

| 호출자 → 제공자 | API | 용도 |
|---|---|---|
| 송신 LNIS → 송신 어댑터 | `GET /sender/health` | 로컬 송신 어댑터 상태 |
| 수신 LNIS → 수신 어댑터 | `GET /receiver/health` | 로컬 수신 어댑터 상태 |
| 송신 LNIS → 송신 어댑터 | `POST /transfers` | 시험 경로와 원본 데이터 또는 I/Q 파일 참조 접수 |
| 수신 어댑터 → 수신 LNIS | `POST /lnis/api/v1/dtn/receive` | 원래 JSON 전달 및 수신 처리 요청 |

- 각 PC의 `dtn_adapter=http://어댑터-IP:포트`를 설정합니다. 전송은 기본 `/transfers`를 사용하며 전체 경로 설정도 허용합니다.
- Health는 주소의 scheme/host/port에 역할별 경로를 붙입니다. 수신 LNIS가 송신 어댑터 Health까지 조회하지 않습니다.
- Health는 10초마다 GET, 최대 5초·16 KiB 응답 제한입니다. 리다이렉트는 따르지 않습니다.
- Health 응답: HTTP 200, `Content-Type: application/json`, `{"status":"ready"}` 또는 `{"status":"busy"}`. 선택 `message` 필드 허용.
- `ready`는 시험 접수 가능, `busy`는 처리 중입니다. 다른 상태는 정상 준비로 판정하지 않으며 비-2xx/연결 오류는 연결 실패입니다.
- LNIS 화면에서 `ready`는 정상연결(`ok=true`), `busy`는 시험대기(`ok=false`)입니다. Health 주기 조회는 화면이 열려 있을 때 이루어집니다. LNIS의 `/dtn/adapter-health`는 내부 조회 API이며 어댑터가 구현할 경로가 아닙니다.
- 현재 Health는 인증 헤더 없는 읽기 전용 API입니다. 토큰·내부 경로 등 민감정보를 반환하지 말고 신뢰된 시험망에서만 제공합니다.
- 별도 모드 설정 API는 사용하지 않습니다. **각 전송 요청의 `senderMode`, `receiverMode`를 보고 기동**합니다.
- EID, lifetime, convergence layer는 어댑터 자체 설정입니다. LNIS JSON에 중복 정의하지 않습니다.

### 15.2 송신 LNIS → 어댑터: 시험별 요청 JSON

공통 HTTP 헤더:

```http
POST /transfers
Content-Type: application/json
Authorization: Bearer <LNIS_DTN_SEND_TOKEN>
```

송신 인증 토큰은 설정한 경우 사용합니다. 운영에서는 토큰을 설정하세요. LNIS는 설정된 전송 URL에만 해당 토큰을 보냅니다.
전체 요청은 UTF-8 JSON이며 최대 16 MiB입니다. 요청 제한 시간은 30초이므로 파일 전달 완료를 기다리지 말고 접수를 응답하세요.

| 공통 필드 | 타입·값 |
|---|---|
| schemaVersion | AFS 신규 전송 `3`, RAW·I/Q `1`; 과거 AFS `1`·`2` 수신 호환 |
| testId | LNIS가 발급한 시험 UUID. 모든 단계에서 유지 |
| testType | `GNSS_RAW`, `AFS_METADATA`, `IQ_SAMPLE` |
| senderMode | `DTN` 또는 `HDTN`: 송신 측 기동 모드 |
| receiverMode | `DTN` 또는 `HDTN`: 수신 측 기동 모드 |
| hdtnConfig | 선택 객체. HDTN이 포함된 경로의 시험별 설정. DTN → DTN 및 기존 설정 없는 요청에서는 생략 |
| profile | RAW/AFS: `POCKETSDR-GPS-L1CA-SPP-v1`, I/Q: `LANS-AFS-IQ-v1` |
| format | 아래 유형별 데이터 형식 |
| referencePvt | 송신 기준 PVT 배열. RAW/AFS는 관측 시점별, 신규 IQ_SAMPLE은 생성 시작 시점 1개. 비교용이며 수신 계산 입력이 아님 |

네 가지 경로 DTN→DTN, DTN→HDTN, HDTN→DTN, HDTN→HDTN을 모두 지원해야 합니다.
선택값은 시험 시작 시 확정되며, 어댑터는 전송 중 UI 변경과 무관하게 이 요청값을 사용합니다.
다음 예시의 Base64·해시는 설명용 자리표시자입니다.
프레임 배열도 설명을 위해 한 항목만 표시했습니다. 예시를 그대로 시험 입력으로 사용하지 마세요. 현재 LNIS가 보내는 모든 필드를 보존해야 하며, 표에 없는 필드가 있어도 삭제하거나 기본값을 새로 추가하지 않습니다.

#### 시험 중지와 늦은 응답 처리

- 송신 화면의 **시험 중지**는 LNIS 대기·준비·계산과 상대 수신 시험을 같은 시험 ID로 중지합니다. 중지 후 이력과 기존 원문·결과 파일은 보존됩니다.
- 상대 노드에 연결되지 않으면 송신 시험을 먼저 중지하고 `cancelPending: true`로 남깁니다. 연결 복구 시 자동 재시도하며 수동 재요청도 가능합니다. 서버 재기동 후에도 이 대기 표시를 유지합니다.
- 관리 API `POST /lnis/api/v1/node/peer/dtn/tests/{id}/cancel`은 기존 관리 토큰으로 인증합니다. 등록보다 중지가 먼저 도착해도 같은 ID의 늦은 등록으로 시험을 다시 시작하지 않습니다.
- 중지된 시험의 외부 callback은 HTTP 409로 거절합니다. 지연된 결과·HTTP 실패·화면 갱신은 `CANCELLED`를 진행 상태로 되돌리지 않습니다.
- HTTP 대기와 I/Q 추적 프로세스는 중지합니다. 실행 중인 네이티브 함수는 메모리를 강제 해제하지 않으며 반환 후 후속 작업·결과 전송을 막습니다. 처리기가 READY가 된 후 다음 시험을 시작할 수 있습니다.
- 외부 어댑터의 취소 API는 현재 규격에 없습니다. 이미 어댑터에 전달된 번들의 회수나 라우터 자체의 전송 중단은 보장하지 않습니다. I/Q 파일 생성 취소는 기존 **생성 취소** 기능을 사용합니다.

#### HDTN 설정 — `hdtnConfig`

송신 화면의 전송 경로 아래에는 DTN/HDTN 설정 영역이 분리되어 있습니다. 현재는 HDTN 설정만 지원하며, DTN 설정 객체는 전송하지 않습니다. 향후 DTN 설정은 `dtnConfig`, HDTN 설정은 `hdtnConfig`로 구분합니다. DTN의 전체 규격을 받기 전까지 `dtnConfig`의 입력란 및 기본값 전송은 추가하지 않습니다.
`POST /lnis/api/v1/dtn/tests`의 `hdtnConfig`가 RAW·AFS·I/Q 모두 외부 `/transfers` JSON의 같은 이름으로 포함됩니다. `dtnConfig`는 HDTN 설정의 별칭이 아닙니다.
설정은 시험 시작 시 확정해 DB에 저장합니다. 수신 콜백은 이 객체까지 그대로 보존해야 합니다.
기존 클라이언트가 이 객체를 생략하면 자동으로 추가하지 않아 기존 요청 동작을 유지합니다.

```json
{
  "hdtnConfig": {
    "maxNumberOfBundlesInPipeline": 50,
    "maxSumOfBundleBytesInPipeline": 50000000,
    "enforceBundlePriority": true,
    "neighborDepletedStorageDelaySeconds": 10,
    "maxBundleSizeBytes": 10485760,
    "tcpclMaxSegmentSizeBytes": 200000,
    "storageDeletionPolicy": "DELETE_AFTER_FORWARDING"
  }
}
```

| 필드 | 의미 | 화면 기본값·입력 범위 |
|---|---|---|
| maxNumberOfBundlesInPipeline | 수신 확인 전 최대 동시 전송 번들 수 | 50 · 1~2147483647 정수 |
| maxSumOfBundleBytesInPipeline | 동시 전송 번들의 최대 합계 용량(Bytes) | 50000000 · 1~9007199254740991 정수 |
| enforceBundlePriority | 번들 우선순위 준수 여부 | true · JSON boolean |
| neighborDepletedStorageDelaySeconds | 상대 저장 공간 부족 시 대기 시간(초) | 10 · 0~2147483647 정수 |
| maxBundleSizeBytes | 번들 한 개의 최대 크기(Bytes) | 10485760 · 1~9007199254740991 정수 |
| tcpclMaxSegmentSizeBytes | TCPCL 최대 세그먼트 크기(Bytes), 번들 전체 크기 및 IP MTU와는 별도 | 200000 · 1400~1000000 정수 |
| storageDeletionPolicy | 어댑터가 적용할 스토리지 삭제 정책명 | DELETE_AFTER_FORWARDING · 영문 대문자로 시작하는 대문자·숫자·밑줄 1~64자 |

화면은 일곱 필드를 전달합니다. 기존 여섯 필드는 필수이며, `tcpclMaxSegmentSizeBytes`는 기존 클라이언트 호환을 위해 API에서 생략할 수 있습니다. 생략하면 외부 전송 JSON에도 임의로 추가하지 않습니다. 브라우저에 저장된 기존 여섯 항목은 유지하고 새 항목만 200000으로 보충합니다. LNIS는 형식과 범위를 검증해 전달하며, 실제 번들 제한·삭제 정책의 지원 여부와 적용은 어댑터가 담당합니다. 이 설정은 LNIS 입력 파일 및 JSON 크기 제한을 변경하지 않습니다.
HDTN → HDTN에서는 동일한 객체를 양쪽 HDTN 설정에 사용하도록 어댑터와 합의해야 합니다. 송신/수신별로 다른 HDTN 설정을 보내는 규격은 현재 포함하지 않습니다.

추가 전달된 2.7 규격에 따라 TCPCL 설정 범위는 1400~1000000 Bytes입니다. HDTN 기본값은 200000, 향후 DTN(ION) 기본값은 1400입니다. DTN 설정은 계속 추가 규격 대기 상태이며 `dtnConfig`는 아직 전송하지 않습니다.

#### DTN(ION) 매핑 참고 — 추가 규격 대기

다음은 전달받은 어댑터 제안서의 설명이며, LNIS가 ION 엔진 동작을 검증하거나 보장한 내용은 아닙니다. 전체 DTN 규격과 어댑터 구현을 확인한 후 DTN 설정 및 툴팁에 반영합니다.

- 어댑터는 공통 JSON 파라미터를 ION의 메모리·우선순위·보관 정책 등으로 변환합니다. LNIS는 `.rc` 파일을 직접 생성하지 않습니다.
- 추가 제안서 4.4 기준으로 ION 모드에서는 `maxNumberOfBundlesInPipeline`과 `neighborDepletedStorageDelaySeconds`가 적용되지 않습니다. 4.1의 근사 제어 설명과 구분해, 해당 두 값을 ION의 엄격한 개수·대기 시간 제한으로 안내하지 않습니다.
- 제안서는 `maxSumOfBundleBytesInPipeline`을 SDR 메모리 설정에, `maxBundleSizeBytes`를 contact 용량·페이로드 산정에, `enforceBundlePriority`를 ION 스케줄러에 매핑한다고 설명합니다. 정확한 변환식과 실제 적용 결과는 어댑터 구현에서 확인합니다.
- `RETAIN`, `DELETE_AFTER_DELIVERY`를 보관 모드로 매핑할 때 메모리 점유와 확인 트래픽이 증가할 수 있으므로, 어댑터가 지원하는 정책과 메모리 예산을 확인해야 합니다.
- 제안서상 ION 설정 변경 시 어댑터가 컨테이너를 재기동하며 약 2~5초의 단절·지연이 발생할 수 있습니다. 이는 해당 어댑터의 예상 동작으로, 모든 ION 구성에 대한 보장은 아닙니다. 연속 전송 중 설정 변경을 피하도록 안내합니다.
- 새 자료에는 `senderMode: "ION"`이 나오지만 현재 LNIS 및 15절의 모드 값은 `DTN`/`HDTN`입니다. `ION` 별칭 지원 또는 모드 이름 변경은 어댑터와 합의 전까지 적용하지 않습니다.
- 기존 합의는 DTN 설정에 `dtnConfig`, HDTN 설정에 `hdtnConfig`를 사용하는 것입니다. 새 자료의 ION 모드에서도 `hdtnConfig`를 읽는다는 설명과 차이가 있으므로 DTN 구현 전 최종 JSON 규격을 확인합니다.
- DTN 설정은 추가 규격 대기 상태를 유지합니다. TCPCL의 ION 기본값 1400 외에 아직 확정되지 않은 변환식·기본값을 임의로 정하지 않습니다.

#### 공통 PVT 필드 — GNSS_RAW / AFS_METADATA

두 유형의 요청에는 다음 `referencePvt`가 함께 들어갑니다. 수신기는 이를 계산 입력으로 사용하지 않고, 수신 데이터로 독립 계산한 PVT와 비교·표시합니다. 어댑터는 재계산하거나 숫자를 반올림하지 않습니다.

```json
{
  "referencePvt": [{
    "week": 2400,
    "towSeconds": 100000.0,
    "positionValid": true,
    "velocityValid": true,
    "ecefMeters": [-3049086.2376831067, 4046274.1024044757, 3861624.97488378],
    "velocityMetersPerSecond": [-674.6603865750864, 417.4570716281086, 1394.7745784465806],
    "receiverClockBiasSeconds": -2.4781070279303227e-13,
    "satellitesUsed": 5,
    "message": ""
  }]
}
```

위 값은 합성 검증 데이터의 예시이며 실측값이 아닙니다. `week`·`towSeconds`는 GPS 관측 시각이고, 위치는 지구 ECEF X/Y/Z(m), 속도는 ECEF X/Y/Z(m/s), 시계 오차는 초(s)입니다. PVT 실패도 전송 가능한 시험이므로 `positionValid=false` 또는 `velocityValid=false`일 수 있고 관련 값은 null/생략될 수 있습니다. 0으로 치환하지 않습니다. 과거 요청에는 `referencePvt`가 없을 수 있습니다.

아래 A/B 예시는 간결성을 위해 `referencePvt`를 생략했습니다. 실제 송신 요청에 포함된 배열은 반드시 콜백까지 그대로 전달합니다.

#### A. GNSS RAW

```json
{
  "schemaVersion": 1,
  "testId": "438a4035-a13c-4b49-a278-0e5fb7f774bd",
  "testType": "GNSS_RAW",
  "senderMode": "DTN",
  "receiverMode": "HDTN",
  "profile": "POCKETSDR-GPS-L1CA-SPP-v1",
  "format": "LNIS-GRAW-RAW-v1",
  "sourceSha256": "<원본 GRAW SHA-256: 대문자 HEX 64자리>",
  "recordCount": 19,
  "prn": 1,
  "grawBase64": "<length-prefixed GRAW 파일 전체 바이트의 Base64>"
}
```

`grawBase64`는 최대 1 MiB 원본 GRAW 파일입니다. 관측 시각·의사거리·도플러·항법 레코드가 포함됩니다.
UBX 직렬 바이트 원문과는 다릅니다. 어댑터는 내용을 해석·반올림·재계산하지 않고 그대로 전달합니다.
`recordCount`는 관측 시점 수가 아니라 GRAW 전체 레코드 수이며 `prn`은 공통 모델의 호환 필드입니다.

#### B. AFS Frame + Metadata

**신규 v3: `satellites[]`에 PRN별 AFS 프레임과 metadata를 함께 묶습니다.** SB2는 GPS 항법정보, SB3/SB4는 원본 `0101…` 패턴입니다. 아래는 일부 필드·레코드를 생략한 구조 예시입니다.

```json
{
  "schemaVersion": 3,
  "testId": "438a4035-a13c-4b49-a278-0e5fb7f774bd",
  "testType": "AFS_METADATA",
  "senderMode": "HDTN",
  "receiverMode": "DTN",
  "profile": "POCKETSDR-GPS-L1CA-SPP-v1",
  "format": "LNIS-AFS-GNSS-v3",
  "sourceSha256": "<복원할 원본 GRAW SHA-256: 대문자 HEX 64자리>",
  "recordCount": 19,
  "prn": 1,
  "satellites": [{
    "constellationId": 0,
    "prn": 19,
    "frames": [{
      "index": 0, "prn": 19, "week": 2400, "afsItow": 83, "toi": 33,
      "navigationRecordIndices": [0, 1, 2], "frameBase64": "<750바이트 AFS 프레임의 Base64>"
    }],
    "metadata": {
      "observations": [{
        "recordIndex": 18, "measurementIndex": 0, "week": 2400, "towSeconds": 100000.0,
        "observation": {
          "constellationId": 0, "satelliteId": 19, "signalId": 0, "frequencyId": 0,
          "pseudorangeMeters": 20453375.918, "carrierPhaseCycles": 0.0, "dopplerHz": -430.0,
          "lockTimeMilliseconds": 1000, "carrierToNoiseDbHz": 45,
          "pseudorangeStdDev": 1, "carrierPhaseStdDev": 1, "dopplerStdDev": 1, "trackingStatus": 1
        }
      }],
      "navigationSupplement": [{"recordIndex": 0, "record": {
        "testId": "<수집 세션 UUID>", "messageId": "<GRAW 레코드 UUID>",
        "sequence": 0, "capturedAt": "2026-09-07T00:00:00Z",
        "navigation": {
          "constellationId": 0, "satelliteId": 19, "signalId": 0,
          "frequencyId": 0, "sfrbxVersion": 2,
          "words": ["<SB2 필드를 비운 unsigned 32-bit 정수 10개>"]
        }
      }}]
    }
  }],
  "metadata": {"commonRecords": [{"recordIndex": 18, "record": {
      "testId": "<수집 세션 UUID>", "messageId": "<GRAW 레코드 UUID>",
      "sequence": 18, "capturedAt": "2026-09-07T00:00:00Z",
      "observation": {
        "receiverTowSeconds": 100000.0, "week": 2400,
        "leapSeconds": 18, "receiverStatus": 1, "rawxVersion": 1,
        "observations": []
      }
  }}]}
}
```

- `satellites[]`는 `(constellationId, prn)`별 묶음입니다. `frames`와 해당 위성의 관측값·보조 항법정보가 나란히 있습니다. 여러 시점·신호·항법 갱신을 배열로 보존하며 프레임마다 같은 관측값을 복제하지 않습니다. 관측값 없는 위성은 `observations: []`, GPS 항법 세트가 없는 위성/다른 GNSS는 `frames: []`일 수 있습니다.
- 최상위 `metadata.commonRecords`에는 공통 관측 시각·상태 및 수집 환경만 둡니다. RAWX의 `observations: []`는 누락이 아니라 위성별 이동을 뜻합니다. 최상위 `frames`는 null/생략입니다.
- `recordIndex`는 원본 GRAW 전체 배열의 0-based 위치이며 `sequence`와 다를 수 있습니다. 공통 레코드와 위성별 `navigationSupplement`를 합치면 `0..recordCount-1`이 중복·누락 없이 완성됩니다. 각 관측값의 `measurementIndex`는 해당 RAWX 내 원래 순서입니다. 수신은 이 두 인덱스로 원본 순서를 복원합니다.
- `pseudorangeMeters`는 **수신기가 이미 측정한 의사거리(m)**입니다. LNIS 수신은 이를 다시 신호에서 구하지 않고 SB2+보조 항법정보와 함께 지구 PVT를 계산합니다. 위상(cycle), 도플러(Hz), C/N₀(dB-Hz), 추적시간(ms), 편차 코드·유효성 비트도 그대로 보존합니다. 이 예시 숫자만 읽고 나머지 필드를 삭제하지 않습니다.
- `frames[].prn`은 실제 GPS PRN(1~32)입니다. `navigationRecordIndices`는 같은 위성의 `navigationSupplement[].recordIndex` 세 개이며 LNAV subframe 1·2·3 순서입니다. 같은 레코드를 여러 프레임이 참조할 수 있습니다.
- `navigation.words`는 **보조 항법 잔여 워드**입니다. 프레임이 참조하는 레코드에서는 SB2가 담당하는 toe/toc, e(상위 31 bit), sqrtA, i0, Ω0, ω, M0, af0/af1 비트를 0으로 비웁니다. GPS 이심률 최하위 1 bit, 보정항·상태·패리티 등은 남깁니다. 참조하지 않는 항법 레코드는 원문 그대로입니다. 이 배열만으로 완성된 SFRBX라고 해석하면 안 됩니다. LNIS가 AFS 복호화 후 채워 복원합니다.
- 수집 환경 레코드가 있으면 `receiver`에 `receiverModel`, `firmwareVersion`, `portName`, `baudRate`, `sessionName`을 보존합니다.
- `index`는 **모든 위성에 걸친 AFS 프레임 번호**(0부터 연속)입니다. `frameBase64`는 750바이트/1,000문자입니다. `week`·`afsItow`·`toi`는 AFS 시간이며 ITOW는 1,200초 구간, TOI는 구간 내 12초 슬롯(0~99)입니다. 최상위 `prn=1`은 이전 공통 모델 호환 필드이며 위성 식별에 쓰지 않습니다.
- SFRBX 메시지 수와 프레임 수는 다릅니다. 합성 예제는 **96건(수집 순번 0~95) → 32 PRN × subframe 1·2·3 → AFS 32개(index 0~31)**입니다. `recordCount=97`은 RAWX 1건까지 포함한 수입니다. 실제 입력은 항법 중복·갱신·누락 때문에 항상 3:1은 아닙니다.
- LNIS 수신이 CRC·0101 패턴·항법 참조·원본 GRAW SHA-256을 검사한 뒤 RAW 표와 PVT를 계산합니다. `referencePvt`는 비교용일 뿐 계산 입력이 아닙니다.
- **어댑터는 satellites·metadata·referencePvt를 포함한 JSON 전체를 보존하여 콜백합니다.** 소수 반올림 금지. GPS LNAV 1·2·3 세트가 필요하며 관측값만 있는 입력은 GNSS RAW 시험을 사용합니다.

과거 `schemaVersion=1` / `LNIS-GRAW-AFS-v1`(SB3/SB4의 GRAW) 및 `schemaVersion=2` / `LNIS-AFS-GNSS-v2`(분리된 frames/metadata.records)는 수신 호환을 유지합니다. 신규 전송은 v3이며 송신·수신 서비스 모두 업데이트해야 합니다. 별도 AFS Frame 오류 주입 시험과 RAW/I/Q 계약은 변경하지 않습니다.


#### C. I/Q Sample: 파일 주소 + 보조 항법정보

아래는 구조 예시입니다. `gpsLnav`, `referencePvt` 배열 내용은 지면상 생략했으며 실제 요청에는 아래 표의 값이 채워집니다.

```json
{
  "schemaVersion": 1,
  "testId": "438a4035-a13c-4b49-a278-0e5fb7f774bd",
  "testType": "IQ_SAMPLE",
  "senderMode": "HDTN",
  "receiverMode": "HDTN",
  "profile": "LANS-AFS-IQ-v1",
  "format": "LNIS-IQ-FILE-v1",
  "file": {
    "filePath": "/exchange/438a4035-a13c-4b49-a278-0e5fb7f774bd.bin",
    "sizeBytes": 2160000000,
    "sha256": "<BIN SHA-256: 대문자 HEX 64자리>",
    "durationSeconds": 90,
    "sampleRateHz": 12000000,
    "sampleFormat": "IQ_INTERLEAVED_INT8",
    "quantizationBits": 2
  },
  "metadata": {
    "signal": "AFSD",
    "pvtMethod": "AFS_IQ_GPS_LNAV_ASSISTED-v1",
    "week": 2400,
    "towSeconds": 100000.0,
    "trajectory": "ECEF_CONSTANT_VELOCITY",
    "prns": [19, 23, 24, 28, 29],
    "gpsLnav": []
  },
  "referencePvt": []
}
```

- **어댑터는 `testType=IQ_SAMPLE`일 때만 `file.filePath`에서 파일을 읽습니다. HTTP 파일 다운로드 URL이 아닙니다.**
- 송신 PC에서 LNIS와 어댑터에 같은 공유 폴더를 `/exchange`로 마운트합니다. Windows의 `C:\\...` 경로를 보내지 않습니다.
- 저장 형식은 I, Q 각각 signed 8-bit, 값은 -3/-1/+1/+3입니다. 2비트 packed 형식이 아닙니다.
- LNIS가 GRAW 기반 AFS 변조기로 생성한 90초 파일이며 실측 RF 기록이 아닙니다. 어댑터는 생성·복조하지 않습니다.
- 송신 LNIS가 생성 완료·파일 크기·SHA-256을 검증한 뒤 요청합니다. `.part` 파일은 가져가지 않습니다.
- 어댑터는 JSON과 BIN을 연계하여 전달합니다. BIN은 DTN/HDTN으로 전달하고 JSON 본문에는 넣지 않습니다.
- 수신 어댑터는 **수신 PC의 별도 공유 폴더**에 동일한 `/exchange/<파일 UUID>.bin` 경로로 복원합니다. 양 PC의 디스크가 같은 것은 아닙니다.
- 임시 파일에 수신한 뒤 완료 파일로 원자적 변경하고, 원본 크기·해시를 확인한 다음 아래 콜백을 호출합니다.
- 경로·크기·해시를 포함해 JSON을 수정하지 않습니다. 테스트 UUID와 파일 UUID는 서로 다를 수 있습니다.
- LNIS가 파일 검증 → I/Q 탐색·추적·AFS CRC 검증 → 관측값 추출 → 보조 LNAV로 지구 PVT 계산을 수행합니다. 어댑터는 계산하지 않고 **metadata·referencePvt까지 변경 없이 전달**합니다.
- 양쪽 완료 파일은 명시적으로 정리할 때까지 보관합니다. 어댑터는 LNIS 소유 송신 파일을 임의 삭제하지 않습니다.

| I/Q 추가 항목 | 의미 / 수신 요구사항 |
|---|---|
| metadata.signal / pvtMethod | `AFSD` / `AFS_IQ_GPS_LNAV_ASSISTED-v1`. I/Q 단독 측위가 아닌 항법정보 보조 방식 |
| metadata.week / towSeconds | BIN 첫 샘플의 GPS 주차·TOW(초). PC/콜백 시각으로 변경 금지 |
| metadata.trajectory | `ECEF_CONSTANT_VELOCITY`: 초기 위치 + 속도 × 샘플 경과시간으로 기준 궤적 비교 |
| metadata.prns | 생성에 사용한 중복 없는 GPS PRN 1~32, 4~32개 |
| metadata.gpsLnav | `{ "prn": 19, "words24": [10개 정수] }` 배열. 선택 PRN마다 LNAV 서브프레임 1·2·3 필수. 워드는 0~16777215의 **패리티 제외 24-bit** 값이며 SFRBX 32-bit 원문이 아님. 최대 320개 레코드 |
| referencePvt | 앞서 정의한 PVT 필드 구조의 배열 1개. 생성 시작 시각의 유효 ECEF 위치·속도·수신기 시계오차. 수신 측은 비교할 때만 사용 |

수신 파일 무결성은 `fileResult.verdict=PASS`, I/Q PVT 오차 측정은 `comparison.verdict=MEASURED`로 구분합니다. 정확도 합격 허용오차는 미설정이며 RAW/AFS의 1 mm 재현성 기준을 RF 추적 합격 기준으로 사용하지 않습니다. 관측/항법 부족 시 PVT 비교는 `INCONCLUSIVE`입니다. metadata 없는 과거 I/Q는 파일 검증만 수행합니다. 이 항목들은 **LNIS 보고서**에 해당하며 어댑터 접수 응답을 확장할 필요는 없습니다.

#### 어댑터의 접수 응답

HTTP 202 권장:

```json
{"testId":"438a4035-a13c-4b49-a278-0e5fb7f774bd","accepted":true}
```

현재 LNIS는 HTTP 2xx를 접수 성공으로 판단합니다. 파일 가져오기·DTN 전달 완료를 의미하지 않습니다.
접수 불가는 비-2xx로 응답하세요(형식 400, 인증 401, 다른 시험 처리 중/충돌 409, 내부 오류 500).
LNIS는 송신 POST를 자동 재시도하지 않습니다. 어댑터는 동일 시험 중복 접수로 프로세스나 파일 전달을 중복 시작하지 않아야 합니다.

### 15.3 수신 어댑터 → 수신 LNIS: 콜백

```http
POST http://<수신-LNIS-IP>:<port>/lnis/api/v1/dtn/receive
Content-Type: application/json
Authorization: Bearer <LNIS_DTN_RECEIVE_TOKEN>
```

콜백에는 선택적으로 최상위 `dtnLogsBase64`를 추가할 수 있습니다. UTF-8 텍스트 로그의 표준 Base64이며 인코딩 문자열 최대 131072자, 상세 로그 최대 500줄입니다. 로그 포함 전체 JSON은 16 MiB 이하여야 합니다. 이 필드 하나만 원본 동일성 비교에서 제외하고 나머지 필드·값·배열은 그대로 검증합니다. 최초 수신 원문에는 이 필드도 보관합니다. 잘못된 Base64/UTF-8 또는 로그 필드 제한 초과는 경고로 남기며 시험 데이터 접수를 막지 않습니다. 500줄을 넘으면 이후 줄은 상세 로그에 저장하지 않고 경고를 추가합니다. 표시·저장용 메시지는 줄당 최대 2000자이며 전체 내용은 최초 수신 JSON 원문에 남습니다.

각 줄은 `[17:12:36.538] [REST API] 메시지` 또는 `[2026-09-16T17:12:36.538+09:00] 메시지` 형식입니다. 날짜 없는 시각은 Asia/Seoul과 수신 날짜를 기준으로 가장 가까운 날짜(±12시간)로 추정하고 다음 줄부터 직전 로그 시각으로 자정 경계를 보정합니다. 장시간 지연·정확한 날짜 식별에는 오프셋 포함 ISO 날짜·시각을 사용하세요. 시각 없는 줄은 직전 로그 시각(첫 줄은 수신 시각)을 사용합니다. 장비 간 시계 오차는 보정하지 않습니다.

LNIS는 어댑터 로그를 `[DTN]` 상세 항목으로 저장하고 관리 채널로 송신 PC에도 공유합니다. 양쪽 화면의 상세 보기 및 로그 다운로드에서 발생 시각순으로 표시합니다. 중복 콜백은 최초 로그를 유지합니다. 기존에 거절되어 저장되지 않은 콜백 로그는 복구할 수 없습니다.

**위 선택적 로그 필드를 제외한 본문은 15.2에서 접수한 JSON 전체 그대로입니다.** `testType`, 두 모드, 원본 데이터 또는 파일 메타데이터를 모두 유지합니다.
별도 외피로 감싸거나 전송 시각·결과 필드를 추가하지 않습니다. JSON 객체 키 순서와 공백은 변경 가능하지만 값·배열 순서는 유지해야 합니다.
바이트 단위 수신 원문은 최초 접수본을 저장합니다. URL·수신 토큰은 어댑터 환경에 설정하고 JSON에 넣지 않습니다.

- RAW/AFS: 전달한 JSON이 준비되면 호출합니다.
- I/Q: 수신 PC 공유 폴더에 BIN 복원이 완료된 후 호출합니다.
- LNIS 간 시험 사전 등록은 LNIS가 처리합니다. 어댑터가 내부 `/node/peer/**` API를 호출하지 않습니다.

성공 응답 HTTP 202:

```json
{"testId":"438a4035-a13c-4b49-a278-0e5fb7f774bd","accepted":true,"state":"WAITING_RECEIVER"}
```

202는 JSON 접수·저장 완료입니다. 이후 수신 LNIS가 RAW/AFS 복원·PVT 계산 또는 I/Q 파일 검증·추적·보조 항법 기반 PVT 계산을 수행합니다.
같은 JSON 재접수는 재계산하지 않고 현재 상태를 반환합니다. 완료 후 재접수하면 state가 COMPLETED일 수도 있습니다.

| 응답 | 의미 |
|---|---|
| 400 | 시험 없음, 변경된 JSON, 잘못된 값·JSON |
| 401 | 수신 Bearer 토큰 누락·불일치 |
| 409 | 수신 노드가 아님, 중지된 시험 또는 신규 접수를 받을 상태가 아님 |
| 413 | JSON 본문 16 MiB 초과 |

400/401/409 오류 본문은 LNIS의 ProblemDetail 형식(`status`, `detail` 등)입니다. 413은 현재 `{"message":"JSON은 16 MiB 이하입니다."}`를 반환합니다.
수신 파일 불일치는 비동기 검증에서 시험 FAILED로 기록될 수 있으므로 202를 최종 성공으로 표시하지 않습니다.
현재 시험 제한 시간은 각 LNIS 노드의 시험 생성·등록 시각 기준 RAW/AFS 10분, I/Q 20분입니다. 단, 수신 노드는 JSON 접수 전 시험 종류를 알 수 없어 기본 10분을 적용합니다. 접수 후에도 타이머를 다시 시작하지 않습니다. 장시간 지연 전달 시험은 이 제한을 먼저 협의해야 합니다. 중지된 시험의 409 응답에는 같은 ID로 재전송하지 말고 새 시험을 시작하세요.

### 15.4 환경 설정·인계 체크리스트

| 설정 주체 | 설정값 | 용도 |
|---|---|---|
| 송신 LNIS | `dtn_adapter=http://<송신-어댑터>:<port>` | POST 전송 및 송신 health 대상 |
| 송신 LNIS·송신 어댑터 | `LNIS_DTN_SEND_TOKEN`과 일치하는 Bearer 비밀값 | 송신 요청 인증 |
| 수신 LNIS | `dtn_adapter=http://<수신-어댑터>:<port>` | 수신 health 대상 |
| 수신 LNIS·수신 어댑터 | `LNIS_DTN_RECEIVE_TOKEN`과 일치하는 Bearer 비밀값 | 콜백 인증. 미설정 시 LNIS 접수 거부 |
| 수신 어댑터 | `http://<수신-LNIS-IP>:<port>/lnis/api/v1/dtn/receive` | JSON 콜백 주소 |
| 각 PC | 동일 논리 경로 `/exchange`의 로컬 공유 폴더 | 해당 PC의 LNIS·어댑터 사이 BIN 접근 |

어댑터 프로그램의 설정 변수 이름은 자유이며 표의 LNIS 변수명과 같은 이름일 필요는 없습니다. 두 인증 연결의 비밀값은 각각 상대방과 일치해야 합니다. LNIS 간 관리 토큰은 어댑터에 전달하지 않습니다.

컨테이너 안의 `localhost`는 해당 컨테이너 자신입니다. 같은 PC라도 컨테이너·WSL·호스트 간에 접근 가능한 주소를 사용하세요. I/Q는 양 PC에 동일한 논리 파일 경로를 사용하며, 한쪽 절대 경로를 다른 값으로 바꿔 콜백하면 동일성 검증에서 거부됩니다.

인수 확인: **3개 시험 × 4개 경로**, 원본 JSON 보존, I/Q 크기·해시 일치, 중복 접수 방지를 검증합니다.
파일 크기는 signed 32-bit 범위를 넘으므로 **64-bit 정수와 스트리밍 I/O**를 사용합니다.
콜백 응답 유실 시 같은 JSON으로 재요청할 수 있습니다. 400/401/409는 원인을 해결하고 재요청하세요.

현재 별도 어댑터 진행률·실패 통지·최종 판정 콜백 API는 없습니다. 송신 접수 실패는 비-2xx, 접수 이후 미도착은 LNIS 시험 제한 시간으로 처리합니다. 더 긴 지연·별도 오류 통지가 필요하면 계약 변경을 먼저 협의합니다.

### 수신 원문 진단 기록

인증된 `/dtn/receive` 요청은 Content-Type/JSON 구조와 관계없이 검증 전에 별도 DB 수신 기록으로 보관합니다. `GET /lnis/api/v1/dtn/receipts`는 최근 50건의 식별자·시각·상태·실패 사유를 반환하고, `GET /lnis/api/v1/dtn/receipts/{id}/body?download=true`로 원본 바이트를 다운로드합니다. 인증 실패는 저장하지 않습니다. 16 MiB 초과 요청은 앞 16 MiB만 보관하고 `truncated=true`, HTTP 413으로 응답합니다. 비정상 본문 저장은 정상 시험 접수와 다르며 기존 HTTP 400/409 및 무결성 검증은 유지합니다.

시험 ID가 확인되고 아직 원본 수신 전인 시험은 검증 거절 시 FAILED(수신 검증 실패)로 전환하여 양쪽 화면에 이유를 표시합니다. 수정 후에는 새 시험을 시작합니다. 시험 ID가 없거나 변경된 경우 임의로 진행 중 시험에 연결하지 않습니다. 완료·중지·이미 정상 접수된 시험 결과는 거절 요청으로 덮어쓰지 않습니다.

수신 화면의 '수신 JSON 원문'에서 거절·식별 불가 요청도 조회합니다. Docker stdout의 `API_BODY`에는 송신·수신 JSON이 들여쓰기되어 기록되며 인증 정보는 마스킹합니다. JSON으로 해석할 수 없는 본문과 바이너리는 크기만 기록합니다. 정확한 수신 바이트는 DB 원문 다운로드로 확인합니다.


추가 호환 필드: `dtnLogs`는 어댑터의 일반 문자열 로그입니다. `dtnLogsBase64`와 함께 최상위 부가 로그로 분리하며 두 필드만 원본 비교에서 제외합니다. 두 형식 모두 상세 로그로 저장·송신 PC 동기화하고 실제 수신 원문에는 보존합니다. 본문 중 `2026-Sep-17 02:14:10` 형식은 UTC로 해석합니다. 시각이 없는 줄은 직전 로그 시각(첫 줄은 수신 시각)을 사용합니다. 실제 장비 로그의 시간대가 다르면 계약 조정이 필요합니다.

수신 기록 선택은 기존 **수신 JSON 원문** 영역에 통합했습니다. 선택한 시험의 접수/거절 원문과 시험 식별 불가 원문을 선택하고, 같은 정렬 보기·JSON 다운로드 기능을 사용합니다. 별도 수신 원문 영역은 사용하지 않습니다. Docker 본문 로그는 각 서비스의 `@Slf4j` 로거를 사용합니다.


### 화면 로그와 Docker 상세 로그
`DtnLogService`는 새 DB 기록과 어댑터 상세 로그를 `@Slf4j`로 함께 출력합니다. `DTN_EVENT`에는 종류(type), 시험/입력 ID(scopeId), DB 순번(sequence), 발생 시각(occurredAt), 단계, 상세 여부, 메시지를 포함합니다. 상세 항목도 INFO 이상으로 출력하므로 화면 상세 토글과 무관하게 Docker에서 확인할 수 있습니다. 기존 이력 조회/복사는 새 이벤트가 아니므로 재출력하지 않습니다.

브라우저 전용 메시지는 `POST /lnis/api/v1/dtn/logs/screen`으로 현재 노드에 전달합니다. 본문은 `{scopeId, occurredAt, level, message}`이며 ID는 선택, 레벨은 INFO/WARN/ERROR, 메시지는 최대 2000자입니다. `type=SCREEN`으로 콘솔에만 출력해 DB 중복 표시를 피합니다. 브라우저가 서버에 연결할 수 없는 동안의 메시지는 화면에만 남으며 재전송하지 않습니다. 도커 출력 순서는 서버 기록 순서이며 과거 어댑터 이벤트의 발생 시각은 occurredAt으로 확인합니다.


### 공통 HTTP API 로깅

`API_START` / `API_END`는 모든 `/lnis/api/v1/` 호출과 외부 HTTP 호출(DTN 전송, 헬스체크, 노드 관리, 서버 탐색)에 적용됩니다. IN/OUT, requestId, traceId, 메서드, URL, HTTP 상태, 소요 시간, 실제 읽고 쓴 바이트 수, 안전한 헤더를 기록합니다. 본문에서 확인 가능한 testId도 표시합니다. 요청을 읽지 않고 거절한 경우 requestBytes는 0일 수 있으며 Content-Length와 구분합니다. WebSocket 메시지는 기존 연결/시험 이벤트 로그를 유지합니다.

`API_BODY`는 변경 요청과 오류, 조회 상태 변화 시 JSON 본문을 들여써 출력합니다. GET 조회 시작은 DEBUG, 최초 응답과 상태 변경은 INFO로 기록하며 정상 반복 조회의 완료 요약은 DEBUG로 낮춥니다. 반복되는 오류도 생략하지 않고 HTTP 4xx·통신 실패는 WARN, 5xx는 ERROR로 기록합니다. POST 등 변경 요청은 INFO를 유지합니다. 상태 비교는 state/status/online/ready/ok/accepted/peerOnline 필드를 사용하며 변화 감지 캐시는 최근 2048개 호출 경로까지만 유지합니다. `/logs/screen`의 본문은 기존 DTN_EVENT와 중복되므로 별도 출력하지 않습니다. 기존 DTN_SEND_BODY / DTN_RECEIVE_BODY는 API_BODY로 통합되었습니다.

Authorization, Cookie, 토큰·비밀번호·secret·API key 이름의 헤더/JSON 필드는 가립니다. URL 쿼리는 이름만 남기고 값은 숨깁니다. 나머지 헤더도 허용된 진단용 헤더만 값을 표시합니다. 바이너리·멀티파트·스트리밍 본문은 출력하지 않으며 크기와 Content-Type/Disposition/Range, 제공되는 ETag/Digest 등으로 확인합니다. JSON 로그 캡처는 방향별 16 MiB까지로 제한하고 초과하거나 파싱 불가능한 본문은 콘솔 본문을 생략합니다. 원문 DB 저장과 다운로드는 그대로 유지합니다.

X-LNIS-Request-ID / X-LNIS-Trace-ID 헤더로 양쪽 HTTP 호출을 연결합니다. 같은 요청 처리 중 외부로 호출하면 traceId를 이어갑니다. 별도 비동기 시험 작업은 본문의 시험 ID로도 연결해 확인합니다. API_FAILURE는 실패 종류와 호출 위치를 기록하고, 업무 로그와 수신 원문 기록은 유지합니다. 로그 목적의 전체 응답 버퍼링은 하지 않으며 기존 HTTP 취소·제한 시간·다운로드 바이트를 보존합니다.

Docker 콘솔의 레벨 표시는 `[WARN]`만 굵은 노랑(ANSI 1;33), `[ERROR]`만 빨강(ANSI 31)으로 출력하고 즉시 색상을 복원합니다. 다른 레벨과 메시지 본문은 색칠하지 않습니다.
