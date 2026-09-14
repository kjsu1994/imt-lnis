# LNIS API 명세서

현재 소스 코드에 구현된 LNIS 중앙 서버의 REST API와 WebSocket 계약입니다.

- 기준 버전: `1.0.0`
- Agent WebSocket protocol: `2`
- 기본 서버 주소: `http://192.168.1.72:8088`
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

## 5.1 DTN Adapter Health

`http
GET /lnis/api/v1/dtn/adapter-health
`

LNIS 서버가 Sender와 Receiver Adapter의 health URL을 동시에 GET으로 확인합니다. 기본 주소는 각각
http://192.168.1.154:8080/sender/health, http://192.168.1.154:8080/receiver/health이며 설정으로 변경할 수 있습니다.
HTTP 2xx만 ok=true이고, 응답 본문 형식은 가정하지 않습니다.

`json
{
  "checkedAt": "2026-09-14T01:00:00Z",
  "sender": {"url": "http://192.168.1.154:8080/sender/health", "ok": true, "httpStatus": 200, "elapsedMillis": 16, "message": "응답 정상"},
  "receiver": {"url": "http://192.168.1.154:8080/receiver/health", "ok": false, "httpStatus": null, "elapsedMillis": 5000, "message": "응답 시간 초과"}
}
`
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
  "receiverModel": "ZED-F9P",
  "firmwareVersion": "1.32",
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
  "protocolVersion": 2,
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

## 13. DTN AFS 전달 / PVT 비교 API

기존 AFS API와 별도 경로다. COM 수집 종료 후 전송 버튼을 눌러 시험한다.
I/Q 생성, RF 송수신, 달 환경 모사는 수행하지 않는다.
외부 프로그램이 BPv7 생성/송신/수신/해제를 담당한다. 아래 JSON 자체는 BPv7 wire 형식이 아니다.

### 13.1 외부 담당자에게 전달할 계약

1. LNIS 중앙 서버가 외부 프로그램의 설정된 송신 URL에 JSON을 POST한다.
2. 외부 프로그램은 이 데이터를 DTN으로 전달한다.
3. 외부 수신부가 동일 JSON을 LNIS 중앙 서버의 수신 URL에 POST한다.
4. 중앙 서버가 별도 Receiver Agent에 전달하고, Receiver가 AFS 복호화와 PVT 계산을 수행한다.

외부 송신 접수 경로 제안: `POST /lnis-dtn/api/v1/transfers`.
실제 경로는 `LNIS_DTN_SEND_URL`로 설정하므로 외부 담당자의 경로에 맞출 수 있다.
송신 시스템은 HTTP 2xx로 접수를 알린다. 접수 성공은 DTN 전달 완료를 의미하지 않는다.
HTTP 송신 대기 제한은 30초이며 자동 재전송은 하지 않는다.
BPv7 source/destination EID, lifetime, convergence layer 등은 외부 프로그램의 설정으로 관리한다.
현재 JSON에는 그 값을 중복 포함하지 않는다.

양방향 Content-Type은 `application/json`, 문자 인코딩은 UTF-8이다.
송신 시 선택적으로 `Authorization: Bearer <LNIS_DTN_SEND_TOKEN>`을 보낸다.
수신 callback에는 `Authorization: Bearer <LNIS_DTN_RECEIVE_TOKEN>`이 필수다.
callback URL과 인증 토큰을 payload에 싣지 않는다.

### 13.2 외부 전달 JSON

다음은 구조 설명용이며 축약한 Base64와 해시는 실제 요청으로 사용할 수 없다.

```json
{
  "schemaVersion": 1,
  "testId": "438a4035-a13c-4b49-a278-0e5fb7f774bd",
  "profile": "POCKETSDR-GPS-L1CA-SPP-v1",
  "format": "LNIS-GRAW-AFS-v1",
  "sourceSha256": "<length-prefixed GRAW 원본의 SHA-256 대문자 hex 64자리>",
  "recordCount": 100,
  "prn": 1,
  "frames": [
    {
      "index": 0,
      "week": 2400,
      "afsItow": 83,
      "toi": 33,
      "frameBase64": "<정확히 750 byte AFS frame의 Base64>"
    }
  ]
}
```

| 필드 | 의미 |
|---|---|
| schemaVersion | 현재 1 |
| testId | 중앙 서버가 발급한 이번 시험 UUID |
| profile | 양쪽 PVT 계산 설정과 지원 신호를 지정하는 불변 프로파일 |
| format | 기존 LNIS GRAW fragment를 AFS SB3/SB4에 넣는 응용 데이터 형식 |
| sourceSha256 | 복원된 length-prefixed GRAW 바이트열 검증용 해시 |
| recordCount | 전체 원본 GRAW 레코드 수 |
| prn | AFS SB2 시험 프로파일 PRN. GPS 관측 위성의 PRN과 다르다. 현재 1 |
| frames | 순서대로 보관한 모든 AFS 프레임 |
| index | 0부터 시작하는 연속 번호 |
| week / afsItow / toi | AFS 프레임 시간 좌표. afsItow는 1,200초 구간 번호 |
| frameBase64 | 750바이트의 부호화된 프레임. 문자열 길이는 1,000자 |

관측 시각(week/TOW), RAWX 관측값, SFRBX 항법 메시지와 수신기 정보는
AFS 프레임 안의 GRAW에 포함한다. 외부 프로그램은 이 내용을 해석할 필요가 없다.
SB2의 고정 LANS 알마낙을 지구 GPS PVT의 항법정보로 사용하지 않는다.
기준 PVT는 외부에 보내지 않는다.

JSON 필드 순서와 공백은 바뀌어도 되지만 필드, 값, 배열 순서는 유지해야 한다.
수신 API는 송신 JSON과 구조적으로 동일한지 확인한다. 필드 추가도 거부한다.
수집 원본은 최대 1 MiB, callback JSON은 최대 16 MiB이다.
외부 시스템의 번들 최대 크기가 더 작으면 연동 전에 분할 계약을 추가해야 한다.

### 13.3 수신 callback

```http
POST /lnis/api/v1/dtn/receive
Content-Type: application/json
Authorization: Bearer <수신용 토큰>
```

본문: 송신받았던 동일 JSON.

```json
{
  "testId": "438a4035-a13c-4b49-a278-0e5fb7f774bd",
  "accepted": true,
  "state": "WAITING_RECEIVER"
}
```

HTTP 202는 검증 및 중앙 DB 저장 완료를 뜻하며 PVT 계산 완료를 뜻하지 않는다.
Receiver가 연결되지 않았으면 대기하며 READY가 되면 전달한다.
같은 callback의 중복 도착은 재계산하지 않고 기존 상태를 반환한다.
변경된 JSON은 400, 수신 대기 상태가 아닌 신규 callback은 409,
인증 실패는 401, JSON 크기 초과는 413이다.
시험을 찾을 수 없으면 현재 서비스의 오류 규칙에 따라 400을 반환한다.

### 13.4 화면용 제어 API

- `GET /lnis/api/v1/dtn/config`: 외부 연동 설정 여부, 계산 프로파일, 입력 크기 상한.
- 수집 시작: 기존 `POST /lnis/api/v1/captures` 사용.
- `POST /lnis/api/v1/dtn/captures/{id}/stop?senderAgentId=sender-1`: 마지막 청크 전달까지 기다리는 수집 종료 명령.
- 브라우저는 해당 수집 ID의 GNSS_STATUS/Stopped 이벤트 확인 후 기존 `POST /captures/{id}/complete`로 확정한다.
- `POST /lnis/api/v1/dtn/tests`: 수집 완료 입력으로 기준 계산, AFS 생성 및 외부 전송을 시작한다.
- `GET /lnis/api/v1/dtn/tests/{id}`: 상태 요약.
- `GET /lnis/api/v1/dtn/tests/{id}/report`: 기준/수신 PVT, 관측 시각별 비교 JSON.

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
  "sendUrl": "http://192.168.1.100:8080/transfers"
}
```

- 생략하거나 공백이면 기존 `LNIS_DTN_SEND_URL`을 사용한다. 화면에는 이 설정을 기본값으로 채운다.
- 화면에서 지정한 URL은 시험 생성 시 DB에 저장하며, 이후 입력란 변경은 진행 중 시험에 영향을 주지 않는다.
- 서버가 해당 URL로 `POST`, `Content-Type: application/json` 요청을 보낸다. 브라우저에서 어댑터를 직접 호출하지 않는다.
- `http`/`https`와 유효한 호스트를 요구한다. 최대 2048자이며 URL 내부 인증 정보, fragment, 잘못된 포트는 거부한다.
- 다른 주소로의 redirect는 따라가지 않는다.
- `LNIS_DTN_SEND_TOKEN`은 정규화된 전체 URL이 기본 설정과 일치할 때만 사용한다. 다른 URL에 비밀 토큰을 자동 전달하지 않는다.
- 별도 URL의 인증 헤더 편집 기능은 제공하지 않는다. 인증이 필요한 어댑터는 운영 기본 URL/토큰 설정을 사용한다.
- `localhost`는 LNIS 서버/컨테이너 기준이다. 접근이 제한된 시험망에서 사용하고 관리 화면/API를 공개망에 노출하지 않는다.
- 시험 요약/보고서 응답의 `sendUrl`로 실제 선택한 대상을 확인한다. 과거 시험에는 이 값이 없을 수 있다.
- `/dtn/config`에 `defaultSendUrl`, `receiveConfigured`, `defaultSendTokenConfigured`를 추가한다. 토큰 자체는 반환하지 않는다.

### 13.5 PVT 의미와 판정


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

### 13.6 운영 설정

운영 폴더의 .env에 설정한 뒤 서버를 재시작한다.

```dotenv
LNIS_DTN_SEND_URL=http://<외부-DTN-송신부>:<port>/lnis-dtn/api/v1/transfers
LNIS_DTN_SEND_TOKEN=<외부에서 요구하는 경우 송신 인증 토큰>
LNIS_DTN_RECEIVE_TOKEN=<LNIS가 발급해 외부 수신부에 전달할 토큰>
```

외부 담당자에게 알릴 callback 주소:
`http://192.168.1.72:8088/lnis/api/v1/dtn/receive`.
IP는 실제 중앙 서버 주소에 맞춰 변경한다.
기존 `server` 실행 모드에서는 Receiver PC의 주소를 callback으로 지정하지 않는다.
`node` 실행 모드에서는 callback을 **수신 노드 주소**로 지정한다. 기존 `server` 모드와 callback 소유권이 다르다.
외부 DTN 미연결 상태의 시험용 왕복 서버는 실제 BPv7 동작을 검증하지 않는다.

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

양쪽 DTN 화면에 송신/수신 JSON 보기 버튼, 원문/정렬 표시 선택, 다운로드를 제공한다.
정렬은 브라우저 표시에만 적용하며 다운로드 파일과 저장된 원문은 변경하지 않는다.

## 14. 독립 노드 관리 API

### 화면에서 수신 노드 연결 설정

DTN 수신 화면은 시험 목록과 보고서를 조회만 한다. 외부 어댑터용 수신 주소는 노드의 baseUrl에 /lnis/api/v1/dtn/receive를 붙여 표시하며, 수신 인증 토큰 설정 여부만 노출한다. JSON 접수 후 AFS 복호화와 PVT 계산은 자동 수행되고, 화면의 새로고침은 재계산을 요청하지 않는다. 수신 노드의 COMPLETED는 수신 계산 완료이며 최종 PASS가 아니다. 관측 시각별 위치/속도 유효성을 표시하고, 기준 PVT와의 최종 비교는 송신 화면에서 확인한다.

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

응답은 `202`와 14.5의 상태 객체다. 같은 ID/해시/참여자의 재등록은 최초 상태를 유지하며, 변경된 등록은 `409`다. 수신 DB에는 송신 JSON, 입력 ID/파일, 기준 PVT를 보관하지 않는다.

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

접수 후 `receivedAt`(UTC), 계산 완료 후 `pvt`(기존 Pvt 배열)가 추가된다. `state=COMPLETED`는 **수신 계산 완료**이며 비교 판정이 아니다. 송신 PC가 해당 PVT와 자체 기준 PVT를 비교하여 최종 verdict를 저장한다. 원본 JSON 및 AFS 프레임은 응답에 포함하지 않는다.

송신 화면의 `dtnReceived=true`는 원격 수신 접수를 뜻하며 송신 DB에 수신 원문이 있다는 뜻이 아니다. `sentPayloadAvailable`/`receivedPayloadAvailable`은 **현재 PC의 DB**를 기준으로 한다. 반대쪽 원문은 Sender/Receiver 버튼으로 해당 PC로 이동해 확인한다.

### 14.6 재시작 및 연결 실패

관리 요청은 고정된 상대 주소만 사용하며 자동 재송신·리다이렉트를 하지 않는다. 연결 실패 중에는 다음 상태 조회를 기다리고 전체 시험 제한 시간을 유지한다. 재시작으로 사라진 AFS 실행은 취소하고, 메모리에서 진행하던 DTN PREPARING/CALCULATING은 FAILED로 기록한다. 영속 저장된 WAITING_DTN/WAITING_RECEIVER는 기존 접수 대기를 계속한다. 수신 대기 제한은 시험 등록 후 10분이다.

역할 선택 화면은 상대 노드 URL로 이동한다. 수신 PC에서 수집/업로드/전송 시작 API를 호출하면 `409`로 거부한다. 기존 `server` 모드의 화면 및 API 동작은 유지한다.
