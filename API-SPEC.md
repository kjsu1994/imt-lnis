# LNIS API 명세서

현재 소스 코드에 구현된 LNIS 송신·수신 통합 노드의 REST API와 브라우저 WebSocket 계약입니다.

- 기준 버전: `1.0.0`
- 통합 노드 내부는 직접 호출하며 외부 REST·브라우저 이벤트 형식은 유지합니다.
- 2026-09-22 확인한 로컬 시험 주소: 송신 `http://192.168.1.72:8088`, 수신 `http://192.168.1.72:8089`
- 운영은 송신·수신 각각 다른 PC입니다. 위 주소는 현재 시험 환경 예시이며 실제 노드 주소·포트로 바꿉니다.
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

노드 간 관리 요청과 어댑터 수신 요청은 각 REST 인증 설정을 사용합니다. 별도 Agent 인증·제어 WebSocket은 제거되었습니다.

### 로컬 GNSS 연결 및 상대 서비스 설정

송신·수신 양쪽 화면에서 사용하며 **외부 DTN/HDTN 어댑터 계약이 아닙니다.** 아래 경로 앞에 `/lnis/api/v1`을 붙입니다.

| 메서드 | 경로 | 동작 |
|---|---|---|
| GET | `/node/gnss/ports` | 로컬 또는 Windows 중계의 포트 목록 |
| GET | `/node/gnss` | 연결·수집·GNSS UTC 상태 |
| POST | `/node/gnss/connect` | 수동 연결. `portName`, `baudRate`, `protocolId:"UBX"`, `dtrEnabled`, `rtsEnabled` |
| POST | `/node/gnss/disconnect?stopCapture=false` | 연결 해제. 수집 중이면 거부; 사용자 확인 후 `true`로 수집도 중단 |
| GET | `/node/connection` | 현재 상대 주소·편집 가능 여부·연결 상태 |
| POST | `/node/connection/test` | 입력 주소 연결 검사만 수행 |
| PUT | `/node/connection` | 상대 주소 검사 후 서버 DB 저장·즉시 적용 |

주소 요청은 `{ "ip":"192.168.219.100", "port":8090, "scheme":"http" }`이며 IPv4를 사용합니다. 인증·상대 ID·반대 역할을 검사하고, 진행 중 시험이 있거나 상대 실행기가 READY가 아니면 저장하지 않습니다. 재시작 시 저장값을 복구합니다.

GNSS `state`: `DISCONNECTED/CONNECTING/CONNECTED/RECONNECTING/ERROR`.
`timeState`: `UNAVAILABLE/ACQUIRING/VALID/STALE`; `utc`, `updatedAt`, `accuracyNanos`는 GNSS 메시지와 그 수신 시각입니다. `VALID`는 **PC 동기화 완료를 뜻하지 않습니다**. 한쪽/양쪽 GNSS 부재 시에도 기존 시스템 시간으로 파일·REST 시험을 계속합니다. 포트 연결만으로 시험 실행기를 BUSY로 만들지 않으며 수집 종료 후 연결은 유지합니다.

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
  "instance": "/lnis/api/v1/dtn/tests",
  "code": "CONFLICT"
}
```

`IllegalArgumentException`과 요청 검증 오류는 `400`, `IllegalStateException`은 `409`입니다.

## 2. API 목록

아래 경로에는 모두 `/lnis/api/v1`을 앞에 붙입니다.

| 구분 | Method | 경로 | 설명 |
|---|---|---|---|
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
| Capture | GET | `/captures/pending` | 시간 초과 후 사용 여부 선택 대기 입력 |
| Capture | POST | `/captures/{captureId}/accept` | 확보한 데이터 사용 승인 |
| Capture | POST | `/captures/{captureId}/discard` | 선택 대기 데이터 폐기 |
| Input | POST | `/inputs/ubx` | UBX 직접 업로드·GRAW 변환 |
| Actuator | GET | `/actuator/health` | 서버 상태 |
| Actuator | GET | `/actuator/health/liveness` | 생존 상태 |
| Actuator | GET | `/actuator/health/readiness` | 준비 상태 |
| Actuator | GET | `/actuator/info` | 서버 정보 |

UBX 직접 입력: `POST /lnis/api/v1/inputs/ubx?fileName=capture.ubx&archiveTime=2026-09-29T00:00:00Z`
(`Content-Type: application/octet-stream`, 본문은 UBX 바이너리). `archiveTime`은 선택 사항이며 파일 보관 시각입니다.
RAWX·SFRBX를 내부 GRAW로 변환·검증한 완료 입력(`inputId`, `recordCount` 등)을 반환합니다.
UBX 최대 64 MiB, 최대 1000 Epoch, 변환 결과 최대 1 MiB이며 관측값 없는 파일은 거부합니다.
PVT 유효성을 보장하는 API가 아니며 이후 기존 관측값·PVT 조회를 사용합니다. 외부 어댑터 계약은 바뀌지 않습니다.

COM 단일 에폭 시간 초과 입력은 `complete=true`, `captureDecision=AWAITING_DECISION`으로 보관됩니다.
승인 전 시험 사용·새 COM 수집을 차단합니다. `accept` 후 `ACCEPTED` 입력은 PVT 없이 RAW 전송 가능,
AFS는 GPS LNAV 항법정보 필요, I/Q는 기존 유효 위치·속도 조건을 유지합니다. `discard`는 선택 대기 입력만 삭제합니다.

## 3. 제거된 구형 인터페이스

`GET /discovery`와 `/lnis/agent/ws`는 더 이상 제공하지 않습니다. 노드 상태는 기존 `/node/peer/status` 관리 API를 사용합니다. `/agents` 조회·COM 요청과 JSON의 `agentId`, `senderAgentId`, `receiverAgentId`는 기존 API 호환을 위해 유지하며 별도 Agent 프로세스를 뜻하지 않습니다.

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

## 7–9. 제거된 독립 AFS 검증시험

Session·결과 산출물·Frame Evidence API는 제거했습니다. DTN의 AFS_METADATA 및 I/Q 코덱은 유지합니다. 기존 AFS DB와 파일은 자동 삭제하지 않습니다.

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

WebSocket은 실시간 표시용입니다. 재접속 시 `/agents` 및 `/dtn/tests` 조회로 현재 상태를 확인합니다.

## 11. 화면 경로

아래는 REST API가 아니라 HTML 화면입니다.

| 경로 | 설명 |
|---|---|
| `/` | DTN Sender로 redirect |
| `/lnis/afstest/sender` | DTN Sender로 redirect |
| `/lnis/afstest/receiver` | DTN Receiver로 redirect |
| `/lnis/test/sender` | 기존 Sender 호환 주소 |
| `/lnis/test/receiver` | 기존 Receiver 호환 주소 |
| `/lnis/dtntest/sender` | DTN 송수신 및 PVT 비교 화면 |
| `/lnis/dtntest/receiver` | 수신 데이터·독립 계산 PVT·읽기 전용 시험 설정 |

## 12. 일반 사용 순서

GRAW 업로드 또는 COM 수집 완료 → DTN 입력 확인 → POST /dtn/tests → GET /dtn/tests/{id} 및 보고서 조회. 상세 계약은 13장을 참고합니다.

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
수신 및 계산 순서 대기는 만료하지 않는다. 실제 PREPARING/CALCULATING 작업에만 단계 시작부터 RAW/AFS 10분, I/Q 20분 제한을 적용한다.
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

신규 RAW/AFS는 1 Epoch 지연 PVT로 위치·속도·Clock Bias 차이를 측정한다.
MEASURED는 측정 완료, PARTIAL은 부분 비교, INCONCLUSIVE는 비교 불가다.
데이터 무결성 일치와 PVT 측정은 별개이며 절대 위치 정확도나 허용오차 합격을 인증하지 않는다.
과거 원본 복원 시험에만 위치·속도 0.001 및 시계 오차 1e-9 s 일치 기준을 사용했다.

### 13.3 운영 설정

화면의 어댑터 주소 `저장·적용`은 해당 브라우저·역할에 저장한다. 새로고침 시 복원하며 서버 `.env`와 인증 토큰은 변경하지 않는다.

운영 폴더 `.env`의 어댑터 주소·인증 설정은 [문서 하단 연동 계약](#adapter-contract)을 따른다. 변경 후 서버를 재시작한다.
운영 `node` 모드의 콜백 대상은 수신 PC의 LNIS이다. 레거시 `server` 모드는 중앙 서버가 접수한다.

### DTN 역할별 화면 및 최근 시험 조회

- Sender 화면: `/lnis/dtntest/sender` — 수집 및 전송 제어
- Receiver 화면: `/lnis/dtntest/receiver` — Agent 상태, 수신 및 PVT 비교 결과 조회
- `GET /lnis/api/v1/dtn/tests`: 생성 시각 내림차순 시험 요약 배열. `page=0`부터 페이지당 50건이며 선택적으로 `state=WAITING_DTN` 필터를 사용한다.
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

### 13. 설정 프리셋 — 송신 서비스 로컬 화면용

동일 송신 서버에서 최대 5개를 공유한다. 수신 노드는 403을 반환한다. 기존 로컬 설정 API와 같은 신뢰 LAN 사용 범위이며, 외부 어댑터 계약이나 `/transfers` JSON은 변경하지 않는다.

| 메서드 | 경로 (`/lnis/api/v1` 기준) | 기능 |
|---|---|---|
| GET | `/dtn/presets` | 목록, 갱신 시각 내림차순·no-store |
| POST | `/dtn/presets` | 새 항목 저장 |
| PUT | `/dtn/presets/{id}` | 이름·설정 변경, 현재 `version` 필요 |
| DELETE | `/dtn/presets/{id}?version=0` | 현재 버전 확인 후 삭제 |

저장 본문: `{ "name": "기본", "version": null, "settings": { "testType": "AFS_METADATA", "senderMode": "HDTN", "receiverMode": "HDTN", "delayEnabled": true, "hdtnConfig": { ... } } }`.
`hdtnConfig`는 기존 검증 범위를 따르는 10개 값 전체이며, 경로가 DTN→DTN이어도 프리셋에는 보관한다. 실제 전송 포함 여부는 기존 시험 경로 규칙을 따른다. JSON의 정수·boolean 타입을 검증하며 주소·파일·선택 Epoch·시험 결과는 저장 대상이 아니다.

목록/저장 응답 항목: `id`, `name`, `version`, `updatedAt`, `settings`. 이름은 양끝 공백 제거 후 1~40자, 대소문자 구분 없이 중복을 금지한다. 잘못된 요청은 400, 없는 항목은 404, 5개 초과·중복 이름·오래된 수정 버전은 409이다. 삭제 성공은 204이며 수정 충돌 시 목록을 갱신하고 다시 불러와야 한다. 프리셋 테이블은 시험 이력 정리·화면 `/clear`와 별도로 유지한다.

송신·수신 화면의 ‘이 시험의 어댑터 요청 설정’은 기존 시험 요약 응답 `hdtnConfig`를 표시한다. 어댑터 실제 적용값을 조회하는 API는 추가하지 않는다. 설정 없는 과거 시험에 기본값을 보충하지 않는다.

## 14. 독립 노드 관리 API

### 화면에서 수신 노드 연결 설정

JSON 접수 후 수신 복원·PVT 계산은 자동 수행된다. 새로고침은 재계산을 요청하지 않는다. COMPLETED는 처리 완료이며 최종 PASS와 구분한다. 수신 화면은 같은 시각의 기준·수신 PVT를 좌우로 표시한다. GNSS RAW·AFS는 기존 일치 판정, I/Q는 허용오차 미설정 상태의 오차 측정(MEASURED)을 표시한다. I/Q 추적 관측값과 보조 LNAV도 표로 표시하되 수신기 RAWX·SFRBX 원본과 구분한다. 메타데이터 없는 과거 I/Q 파일은 파일 검증만 수행한다.

독립 송신 노드의 DTN 화면에서 공통으로 사용한다. 관리 토큰은 서버 설정을 사용하며 요청·응답에 토큰 값을 넣지 않는다.

DTN 화면은 기존 수신측 IP·Port 옆에 연결 테스트와 저장·적용을 배치한다. 이 값은 수신 LNIS 관리 주소이며, 바로 아래의 DTN/HDTN 전송 URL과 별개다. 전송 URL은 전체 주소(HTTPS·경로·쿼리 포함)를 그대로 시험 생성 요청의 sendUrl로 전달한다. 연결 상태는 저장된 수신 노드 기준이고, 후보 주소의 테스트 결과는 버튼 아래에 별도로 표시한다.

- `GET /lnis/api/v1/node/connection`: 현재 `ip`, `port`, `scheme`, `baseUrl`, `peerAgentId`, `tokenConfigured`, `editable`, `busy` 반환.
- `POST /lnis/api/v1/node/connection/test`: `{"ip":"192.168.1.73","port":8088}`. 서버가 후보 수신 노드에 인증된 GET 상태 요청을 보낸다. `connected`, `ready`, `elapsedMilliseconds`, `message`, 정상 조회 시 `node` 반환. 현재 주소는 변경하지 않는다.
- `PUT /lnis/api/v1/node/connection`: 같은 본문으로 연결을 다시 확인하고 READY인 경우 H2에 저장·적용한다. DTN 시험 진행 중이거나 연결 검증 실패 시 기존 설정을 유지한다. `scheme`은 생략 시 `http`이며 기존 HTTPS 설정도 지원한다.

화면 저장값은 환경 변수 `LNIS_NODE_PEER_URL`보다 우선하며 재시작 후 유지된다. IPv4/포트만 입력하며 URL 경로·호스트명·미지정/멀티캐스트/링크 로컬 주소는 거부한다. 잘못된 입력은 `400`, 시험 중 변경 등 상태 오류는 `409`다. 연결 테스트의 접속/인증 오류는 `200`과 `connected=false`로 표시한다. 이 검사는 관리 REST 연결 검사이며 외부 DTN 전달은 검증하지 않는다.

`node` 실행 모드에서만 활성화된다. 기존 `server`, `sender`, `receiver` 실행 계약은 유지한다.
로컬 실행기와 DTN 원격 등록·중지·결과 조회 및 수신 DB 분리를 지원한다.
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

응답은 `202`와 14.5의 상태 객체다. 같은 ID/해시/참여자의 재등록은 최초 상태를 유지하며, 변경된 등록은 `409`다. 등록 단계에서는 송신 JSON, 입력 ID/파일, 기준 PVT를 전달하지 않는다. RAW/AFS의 원본·Reference는 수신 계산 후 송신 관리 API로 별도 조회한다. I/Q callback은 기존 Reference를 포함한다.

송신 노드는 사전 등록 성공 후에만 외부 DTN/HDTN URL로 기존 Transfer JSON을 POST한다. 외부 wire schema는 15절의 RAW v2 / AFS v5 / I/Q v2를 사용한다. 외부 수신 callback은 기존 `/lnis/api/v1/dtn/receive`를 **수신 PC**에서 호출한다. 외부 callback 토큰과 관리 토큰은 별개다.

### 14.5 DTN 수신 결과 조회

`GET /lnis/api/v1/node/peer/dtn/tests/{testId}` — 관리 토큰 필수, 수신 노드 전용.

```json
{
  "testId": "2b23c8bb-f002-4b83-a8e1-8ec7fe1eeb59",
  "state": "WAITING_DTN",
  "message": "외부 DTN/HDTN 수신 대기"
}
```

접수 후 `receivedAt`(UTC), 계산 완료 후 `pvt`(기존 Pvt 배열)가 추가된다. I/Q는 `fileResult`도 반환하며, 보조 항법정보가 있는 신규 시험은 추적 기반 `pvt`를 포함한다. `state=COMPLETED`는 **수신 처리 완료**이며 PVT 일치 판정이 아니다. 송신 PC가 결과를 받아 비교 판정을 저장한다. 어댑터 부가 로그는 수신 노드에서만 보관하며 관리 응답으로 전송하지 않는다. 기존 `adapterLogs` 필드는 구버전 응답 역직렬화 호환용으로 남기지만 송신 로그로 가져오지 않는다. LNIS 자체 처리 로그·원본 JSON·AFS 프레임도 관리 응답에 포함하지 않는다.

송신 화면의 `dtnReceived=true`는 원격 수신 접수를 뜻하며 송신 DB에 수신 원문이 있다는 뜻이 아니다. `sentPayloadAvailable`/`receivedPayloadAvailable`은 **현재 PC의 DB**를 기준으로 한다. 반대쪽 원문은 Sender/Receiver 버튼으로 해당 PC로 이동해 확인한다.

### 14.6 재시작 및 연결 실패

관리 요청은 설정된 상대 주소를 사용하며 리다이렉트를 따르지 않는다. 시험 데이터는 자동 재송신하지 않고, 연결 실패 중에는 다음 상태 조회를 기다린다. 중지 요청은 예외로 `cancelPending=true`인 동안 재시도한다. 재시작 시 메모리에서 진행하던 DTN PREPARING/CALCULATING은 FAILED로 기록한다. 영속 저장된 WAITING_DTN/WAITING_RECEIVER는 기존 접수 대기를 계속한다. 수신·계산 순서 대기는 만료하지 않는다. 준비·계산 작업만 단계 시작부터 RAW/AFS 10분, I/Q 20분 제한을 적용한다.

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
| schemaVersion | AFS 신규 `4`, RAW `1`, I/Q 신규 `2`; 과거 AFS `1`·`2`·`3` 및 I/Q `1` 수신 호환 |
| testId | LNIS가 발급한 시험 UUID. 모든 단계에서 유지 |
| testType | `GNSS_RAW`, `AFS_METADATA`, `IQ_SAMPLE` |
| senderMode | `DTN` 또는 `HDTN`: 송신 측 기동 모드 |
| receiverMode | `DTN` 또는 `HDTN`: 수신 측 기동 모드 |
| hdtnConfig | 선택 객체. HDTN이 포함된 경로의 시험별 설정. DTN → DTN 및 기존 설정 없는 요청에서는 생략 |
| profile | RAW/AFS: `POCKETSDR-GPS-L1CA-SPP-v1`, I/Q: `LANS-AFS-IQ-v1` |
| format | 아래 유형별 데이터 형식 |
| referencePvt | I/Q 전송의 생성 시작 기준 PVT 1개. 신규 RAW/AFS 전송에서는 제외하고 계산 후 LNIS 관리 API로 조회 |

네 가지 경로 DTN→DTN, DTN→HDTN, HDTN→DTN, HDTN→HDTN을 모두 지원해야 합니다.
선택값은 시험 시작 시 확정되며, 어댑터는 전송 중 UI 변경과 무관하게 이 요청값을 사용합니다.
다음 예시의 Base64·해시는 설명용 자리표시자입니다.
프레임 배열도 설명을 위해 한 항목만 표시했습니다. 예시를 그대로 시험 입력으로 사용하지 마세요. 현재 LNIS가 보내는 모든 필드를 보존해야 하며, 표에 없는 필드가 있어도 삭제하거나 기본값을 새로 추가하지 않습니다.

#### 시험 중지와 늦은 응답 처리

- 송신 화면의 **시험 중지**는 LNIS 대기·준비·계산과 상대 수신 시험을 같은 시험 ID로 중지합니다. 중지 후 이력과 기존 원문·결과 파일은 보존됩니다.
- 상대 노드에 연결되지 않으면 송신 시험을 먼저 중지하고 `cancelPending: true`로 남깁니다. 연결 복구 시 자동 재시도하며 수동 재요청도 가능합니다. 서버 재기동 후에도 이 대기 표시를 유지합니다.
- 관리 API `POST /lnis/api/v1/node/peer/dtn/tests/{id}/cancel`은 기존 관리 토큰으로 인증합니다. 등록보다 중지가 먼저 도착해도 같은 ID의 늦은 등록으로 시험을 다시 시작하지 않습니다.
- 중지된 시험의 정상 외부 callback은 원문만 보관하고 HTTP 202와 `accepted:false`를 반환합니다. 지연된 결과·HTTP 실패·화면 갱신은 `CANCELLED`를 진행 상태로 되돌리지 않습니다.
- HTTP 대기와 I/Q 추적 프로세스는 중지합니다. 실행 중인 네이티브 함수는 메모리를 강제 해제하지 않으며 반환 후 후속 작업·결과 전송을 막습니다. 처리기가 READY가 된 후 다음 시험을 시작할 수 있습니다.
- 외부 어댑터의 취소 API는 현재 규격에 없습니다. 이미 어댑터에 전달된 번들의 회수나 라우터 자체의 전송 중단은 보장하지 않습니다. I/Q 파일 생성 취소는 기존 **생성 취소** 기능을 사용합니다.

#### HDTN 설정 — `hdtnConfig`

송신 화면은 HDTN 설정만 제공하며 기본 7개와 접을 수 있는 고급 3개 항목으로 구성됩니다. DTN 준비 영역은 제거했으며 `dtnConfig`는 전송하지 않습니다.
`POST /lnis/api/v1/dtn/tests`의 `hdtnConfig`는 RAW·AFS·I/Q 외부 `/transfers` JSON에 같은 이름으로 포함됩니다. HDTN이 포함된 경로에서만 전송하고, 시험 시작 시 DB에 확정 저장합니다. 수신 콜백은 이 객체를 그대로 보존해야 합니다.

```json
{
  "hdtnConfig": {
    "maxNumberOfBundlesInPipeline": 50,
    "maxSumOfBundleBytesInPipeline": 50000000,
    "maxBundleSizeBytes": 10485760,
    "tcpclMaxSegmentSizeBytes": 20000,
    "neighborDepletedStorageDelaySeconds": 10,
    "enforceBundlePriority": false,
    "storageDeletionPolicy": "DELETE_AFTER_FORWARDING",
    "totalStorageCapacityBytes": 8589934592,
    "maxLtpReceiveUdpPacketSizeBytes": 65536,
    "acsSendPeriodMilliseconds": 1000
  }
}
```

| 필드 | 화면 기본값 | 신규 시험 API 허용 범위 |
|---|---|---|
| maxNumberOfBundlesInPipeline | 50 | 10~10,000 정수 (개) |
| maxSumOfBundleBytesInPipeline | 50000000 | 1,048,576~2,147,483,648 정수 (Bytes) |
| maxBundleSizeBytes | 10485760 | 1,048,576~104,857,600 정수 (Bytes) |
| tcpclMaxSegmentSizeBytes | 20000 | 20,000~200,000 정수 (Bytes) |
| neighborDepletedStorageDelaySeconds | 10 | 0~3,600 정수 (초) |
| enforceBundlePriority | false | JSON boolean |
| storageDeletionPolicy | DELETE_AFTER_FORWARDING | `DELETE_AFTER_FORWARDING`, `on_expiration`, `on_storage_full`, `never` |
| totalStorageCapacityBytes | 8589934592 | 1~9,007,199,254,740,991 정수 (Bytes) |
| maxLtpReceiveUdpPacketSizeBytes | 65536 | 1~2,147,483,647 정수 (Bytes) |
| acsSendPeriodMilliseconds | 1000 | 1~2,147,483,647 정수 (ms) |

용량 범위는 MiB/GiB 기준입니다. 고급 3개 항목의 상한은 자료형 및 JSON 정수 정밀도 제한이며 실제 엔진 허용 범위를 보장하지 않습니다. `DELETE_AFTER_FORWARDING`은 어댑터 문서상 `never`로 매핑되며 LNIS가 즉시 삭제를 보장하지 않습니다. 정책 문자열은 변환하지 않고 전달합니다.

화면은 10개 값을 전달합니다. 기존 여섯 필드는 필수이고 TCPCL 및 고급 3개 필드는 API에서 생략할 수 있습니다. 생략한 값이나 `hdtnConfig` 객체를 임의로 추가하지 않습니다. 숫자 문자열·소수·빈 값·범위 밖 값과 목록 외 정책은 신규 시험 요청에서 거절합니다. 과거 저장된 원문과 설정 조회에는 새 요청 검증을 적용하지 않습니다.

‘전체 기본값’은 고급 설정까지 한 번에 복원하고 브라우저에 저장합니다. 기존 저장값은 유효한 항목을 유지하며, 새 범위를 벗어난 항목만 기본값으로 복구하고 알립니다. 입력 오류는 해당 칸에 남기고 저장·시험 시작을 막습니다. 시험 중에는 설정 변경과 초기화를 잠급니다.
이번 변경은 `hdtnConfig`에 한정하며 `convergenceLayer`, schemaVersion, testId 규격과 라우터 파일 생성은 변경하지 않습니다. HDTN → HDTN은 기존처럼 하나의 설정 객체를 전달합니다.

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

#### A. GNSS RAW — 변환 관측 JSON

신규 요청은 1 Epoch이며 원본 GRAW Base64 대신 `raw.records`를 전달합니다.
관측의 `pseudorangeMeters`를 제거하고 송신부에서 계산한 `transmitAt`으로 대체합니다.
나머지 선택 RAW 레코드 필드는 유지합니다. 아래는 구조 예시이며 완성된 PVT 입력이 아닙니다.

```json
{
  "schemaVersion": 2,
  "testId": "<UUID>",
  "profile": "POCKETSDR-GPS-L1CA-SPP-v1",
  "format": "LNIS-GRAW-DELAY-v2",
  "testType": "GNSS_RAW",
  "senderMode": "HDTN",
  "receiverMode": "HDTN",
  "raw": {
    "startedAt": "2026-09-28T01:00:00Z",
    "records": [{
      "testId": "<원본 레코드 UUID>",
      "messageId": "<원본 메시지 UUID>",
      "sequence": 96,
      "capturedAt": "2026-09-07T00:00:00Z",
      "observation": {
        "week": 2400,
        "receiverTowSeconds": 100000.0,
        "leapSeconds": 18,
        "receiverStatus": 0,
        "observations": [{
          "constellationId": 0,
          "satelliteId": 19,
          "signalId": 0,
          "dopplerHz": -100.0,
          "carrierToNoiseDbHz": 45,
          "trackingStatus": 1,
          "transmitAt": {"seconds": 1790557199, "femtoseconds": 929951540008388}
        }]
      }
    }]
  }
}
```

예시에서 다른 RAW 필드와 선행 항법 레코드는 생략했습니다.
`transmitAt.seconds`는 Unix 정수 초, `femtoseconds`는 0~999999999999999의 정수입니다.
큰 절대시각을 double 하나로 합치거나 소수로 반올림하지 마세요.
유효한 양의 원본 의사거리가 없으면 `transmitAt:null`이며 유효 의사거리로 사용하지 않습니다.

#### B. AFS Frame — 프레임만으로 수신 PVT 계산

```json
{
  "schemaVersion": 5,
  "testId": "<UUID>",
  "profile": "POCKETSDR-GPS-L1CA-SPP-v1",
  "format": "LNIS-AFS-GNSS-v5",
  "testType": "AFS_METADATA",
  "senderMode": "HDTN",
  "receiverMode": "HDTN",
  "satellites": [{
    "constellationId": 0,
    "prn": 19,
    "frames": [{
      "index": 0,
      "prn": 19,
      "week": 2400,
      "afsItow": 83,
      "toi": 33,
      "frameBase64": "<750 bytes의 Base64: 1000문자>"
    }]
  }]
}
```

- `testType=AFS_METADATA`는 API 식별자를 유지한 것이며 신규 v5에는 metadata가 없습니다.
- GPS L1 관측별 1프레임, 전체 순번 `index`는 0부터 연속입니다. 선택한 단일 Epoch만 전송합니다.
- SB2 항법정보, SB3 보충 항법정보, SB4 원본 GNSS 시각·가상 송신 시각·Doppler·C/N₀·상태·전리층·시험 시작 시각을 담습니다.
- SB3/SB4 확장 type=63, version=3은 LNIS 시험용 식별이며 공식 메시지 할당을 뜻하지 않습니다.
- SB3 사용 517/846비트, SB4 사용 670/846비트이며 각각 미사용 데이터는 `010101…`로 채웁니다. 상세 비트 도식은 README를 참조하세요.
- 원본 의사거리·Reference·원본 RAW metadata·원본 GRAW 해시를 외부 JSON에 넣지 않습니다.
- 위 RAW/AFS 예시에는 `hdtnConfig`를 생략했습니다. 실제 요청은 확정한 HDTN 설정을 같은 이름으로 포함합니다.
- 어댑터는 JSON 전체를 그대로 콜백합니다. 자체 계산·필드 삭제·숫자 반올림은 하지 않습니다.
- RAW/AFS의 원본과 Reference는 수신 계산 이후 LNIS 관리 REST로만 조회합니다. I/Q는 아래 별도 규격입니다.
- 과거 RAW v1, AFS v1~v4는 신규 송신 규격이 아닙니다. 양쪽 LNIS를 함께 업데이트해야 합니다.

#### C. I/Q Sample: 파일 주소 + 프레임 항법정보

`referencePvt`의 각 항목은 `week`, `towSeconds`, `positionValid`, `velocityValid`,
`ecefMeters:[X,Y,Z]`, `velocityMetersPerSecond:[X,Y,Z]`, `receiverClockBiasSeconds`, `satellitesUsed`, `message`입니다.
좌표는 지구 ECEF(m), 속도는 m/s, Clock Bias는 초이며 무효값은 null/생략하고 0으로 만들지 않습니다.
RAW/AFS의 별도 Reference 조회도 같은 PVT 필드를 사용합니다.

신규 파일은 `schemaVersion=2`, `format=LNIS-IQ-FILE-v2`, `metadata.pvtMethod=AFS_IQ_FRAME_PVT-v2`입니다. 파일 크기·90초·12 MHz·경로 규칙과 JSON 필드 구조는 유지합니다. I/Q는 기존 version=2 SB3·SB4 관측 내용을 유지하며 신규 AFS 지연 v5와 구분합니다.

수신은 CRC 검증된 SB2·SB3·SB4의 항법정보와 실제 I/Q 추적 관측값으로 PVT를 계산합니다. 새 방식의 `metadata.gpsLnav`는 기존 구조의 부가자료이며 계산에 사용하지 않습니다(빈 배열도 허용). 필요한 프레임을 복원하지 못하면 JSON으로 대체하지 않습니다. Reference·SB4 원본 관측값을 I/Q 추적 측정값으로 대신하지 않습니다. `metadata.week/towSeconds/prns`는 샘플 시각과 탐색 채널 설정에 계속 사용합니다.

아래는 신규 v2 구조 예시입니다. 과거 v1의 JSON 항법 보조 방식은 아래 표에서 별도로 구분합니다.

아래는 구조 예시입니다. `gpsLnav`, `referencePvt` 배열 내용은 지면상 생략했으며 실제 요청에는 아래 표의 값이 채워집니다.

```json
{
  "schemaVersion": 2,
  "testId": "438a4035-a13c-4b49-a278-0e5fb7f774bd",
  "testType": "IQ_SAMPLE",
  "senderMode": "HDTN",
  "receiverMode": "HDTN",
  "profile": "LANS-AFS-IQ-v1",
  "format": "LNIS-IQ-FILE-v2",
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
    "pvtMethod": "AFS_IQ_FRAME_PVT-v2",
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
- LNIS가 파일 검증 → I/Q 탐색·추적·AFS CRC 검증 → 관측값 추출 → 프레임에서 복원한 항법정보로 지구 PVT 계산(과거 v1은 JSON LNAV 보조)을 수행합니다. 어댑터는 계산하지 않고 **metadata·referencePvt까지 변경 없이 전달**합니다.
- 양쪽 완료 파일은 명시적으로 정리할 때까지 보관합니다. 어댑터는 LNIS 소유 송신 파일을 임의 삭제하지 않습니다.

| I/Q 추가 항목 | 의미 / 수신 요구사항 |
|---|---|
| metadata.signal / pvtMethod | `AFSD` / 신규 `AFS_IQ_FRAME_PVT-v2`: 프레임 항법 + RF 추적 관측값. 과거 `AFS_IQ_GPS_LNAV_ASSISTED-v1`은 JSON 항법 보조 방식 |
| metadata.week / towSeconds | BIN 첫 샘플의 GPS 주차·TOW(초). PC/콜백 시각으로 변경 금지 |
| metadata.trajectory | `ECEF_CONSTANT_VELOCITY`: 초기 위치 + 속도 × 샘플 경과시간으로 기준 궤적 비교 |
| metadata.prns | 생성에 사용한 중복 없는 GPS PRN 1~32, 4~32개 |
| metadata.gpsLnav | `{ "prn": 19, "words24": [10개 정수] }` 배열. 신규 v2에서는 계산에 사용하지 않는 부가자료로 빈 배열 허용. 과거 v1에서는 선택 PRN마다 LNAV 서브프레임 1·2·3 필수. 워드는 0~16777215의 **패리티 제외 24-bit** 값이며 SFRBX 32-bit 원문이 아님. 최대 320개 레코드 |
| referencePvt | 앞서 정의한 PVT 필드 구조의 배열 1개. 생성 시작 시각의 유효 ECEF 위치·속도·수신기 시계오차. 수신 측은 비교할 때만 사용 |

수신 파일 무결성은 `fileResult.verdict=PASS`, I/Q PVT 오차 측정은 `comparison.verdict=MEASURED`로 구분합니다. 정확도 합격 허용오차는 미설정이며 RAW/AFS의 1 mm 재현성 기준을 RF 추적 합격 기준으로 사용하지 않습니다. 관측/항법 부족 시 PVT 비교는 `INCONCLUSIVE`입니다. metadata 없는 과거 I/Q는 파일 검증만 수행합니다. 이 항목들은 **LNIS 보고서**에 해당하며 어댑터 접수 응답을 확장할 필요는 없습니다.

수신기의 `-tscale 20`은 LNIS 내부 파일 재생 옵션이며 외부 JSON 필드가 아닙니다. 신호 시각·Doppler·샘플링 주파수는 바꾸지 않고, 분석이 밀리면 읽기를 대기하며 EOF 잔여 분석 완료 후 종료합니다. 처리 정지·읽기 오류·취소는 정상 완료로 판정하지 않습니다. 어댑터의 전달 계약은 변경하지 않습니다.

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

LNIS는 어댑터 로그를 수신 노드의 `[DTN]` 상세 항목으로 저장합니다. 수신 화면의 상세 보기 및 로그 다운로드에서 발생 시각순으로 표시하며 송신 PC에 복사하지 않습니다. 중복 콜백은 최초 로그를 유지합니다. 기존에 거절되어 저장되지 않은 콜백 로그는 복구할 수 없습니다.

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

202는 JSON 접수·저장 완료입니다. 이후 수신 LNIS가 RAW/AFS 복원·PVT 계산 또는 I/Q 파일 검증·추적·프레임 항법 기반 PVT 계산을 수행합니다(과거 I/Q는 JSON 항법 보조).
같은 JSON 재접수는 재계산하지 않고 현재 상태를 반환합니다. 완료 후 재접수하면 state가 COMPLETED일 수도 있습니다.

| 응답 | 의미 |
|---|---|
| 400 | 시험 없음, 변경된 JSON, 잘못된 값·JSON |
| 401 | 수신 Bearer 토큰 누락·불일치 |
| 409 | 수신 노드가 아님 또는 신규 접수를 받을 상태가 아님 |
| 413 | JSON 본문 16 MiB 초과 |

400/401/409 오류 본문은 LNIS의 ProblemDetail 형식(`status`, `detail` 등)입니다. 413은 현재 `{"message":"JSON은 16 MiB 이하입니다."}`를 반환합니다.
수신 파일 불일치는 비동기 검증에서 시험 FAILED로 기록될 수 있으므로 202를 최종 성공으로 표시하지 않습니다.
수신 대기는 시간 제한 없이 유지한다. 단, 등록된 testId와 인증·원문 해시 검증을 통과해야 한다. 종료된 시험은 원문만 보관하고 HTTP 202, `accepted:false`, `state:CANCELLED`를 반환한다. 삭제·미등록·변조 데이터는 정상 시험으로 수락하지 않는다. 번들 수명(TTL)은 라우터 설정이며 LNIS 대기 정책과 별개다.

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


추가 호환 필드: `dtnLogs`는 어댑터의 일반 문자열 로그입니다. `dtnLogsBase64`와 함께 최상위 부가 로그로 분리하며 두 필드만 원본 비교에서 제외합니다. 두 형식 모두 수신 상세 로그로 저장하고 실제 수신 원문에 보존합니다. 송신 PC에는 동기화하지 않습니다. 본문 중 `2026-Sep-17 02:14:10` 형식은 UTC로 해석합니다. 시각이 없는 줄은 직전 로그 시각(첫 줄은 수신 시각)을 사용합니다. 실제 장비 로그의 시간대가 다르면 계약 조정이 필요합니다.

수신 기록 선택은 기존 **수신 JSON 원문** 영역에 통합했습니다. 선택한 시험의 접수/거절 원문과 시험 식별 불가 원문을 선택하고, 같은 정렬 보기·JSON 다운로드 기능을 사용합니다. 별도 수신 원문 영역은 사용하지 않습니다. Docker 본문 로그는 각 서비스의 `@Slf4j` 로거를 사용합니다.


### 화면 로그와 Docker 상세 로그
`DtnLogService`는 새 DB 기록과 어댑터 상세 로그를 `@Slf4j`로 함께 출력합니다. `DTN_EVENT`에는 종류(type), 시험/입력 ID(scopeId), DB 순번(sequence), 발생 시각(occurredAt), 단계, 상세 여부, 메시지를 포함합니다. 상세 항목도 INFO 이상으로 출력하므로 화면 상세 토글과 무관하게 Docker에서 확인할 수 있습니다. 기존 이력 조회/복사는 새 이벤트가 아니므로 재출력하지 않습니다.

브라우저 전용 메시지는 `POST /lnis/api/v1/dtn/logs/screen`으로 현재 노드에 전달합니다. 본문은 `{scopeId, occurredAt, level, message}`이며 ID는 선택, 레벨은 INFO/WARN/ERROR, 메시지는 최대 2000자입니다. `type=SCREEN`으로 콘솔에만 출력해 DB 중복 표시를 피합니다. 브라우저가 서버에 연결할 수 없는 동안의 메시지는 화면에만 남으며 재전송하지 않습니다. 도커 출력 순서는 서버 기록 순서이며 과거 어댑터 이벤트의 발생 시각은 occurredAt으로 확인합니다.


### 공통 HTTP API 로깅

`API_START` / `API_END`는 모든 `/lnis/api/v1/` 호출과 외부 HTTP 호출(DTN 전송, 헬스체크, 노드 관리)에 적용됩니다. IN/OUT, requestId, traceId, 메서드, URL, HTTP 상태, 소요 시간, 실제 읽고 쓴 바이트 수, 안전한 헤더를 기록합니다. 본문에서 확인 가능한 testId도 표시합니다. 요청을 읽지 않고 거절한 경우 requestBytes는 0일 수 있으며 Content-Length와 구분합니다. WebSocket 메시지는 기존 연결/시험 이벤트 로그를 유지합니다.

`API_START`와 헤더는 DEBUG입니다. 정상 GET/HEAD/OPTIONS 조회 및 `/logs/screen` 전달 성공은 DEBUG이며, 연결 상태 변화와 변경 API 결과는 INFO입니다. 반복 연결 실패를 포함하여 HTTP 4xx·통신 실패는 매번 WARN, 5xx는 ERROR로 남깁니다. 정상 목록/보고서/원문 조회는 INFO에서 본문을 재출력하거나 상태 비교용으로 전체 응답을 보관하지 않습니다.

`API_BODY`는 실제 OUT `POST /transfers`, IN `POST /lnis/api/v1/dtn/receive` 원문(검증 거절 포함)을 INFO에 들여써 기록합니다. 다른 API의 본문은 DEBUG입니다. 화면 로그 전달 본문은 이벤트와 중복되므로 출력하지 않습니다. 비어 있는 REQUEST/RESPONSE는 생략하며 요청 ID를 시작·종료에 표시합니다.

IN 로그의 URL은 요청 전체 주소이며 `peer=클라이언트IP:포트`, `local=서버IP:포트`, `mapping=매핑경로`를 추가합니다. 주소는 Servlet 기준으로 Docker/NAT/프록시의 영향을 받을 수 있습니다. 컨트롤러·메서드명은 남기지 않습니다. OUT은 URL에 상대 서버 주소·포트가 포함됩니다.

Authorization, Cookie, 토큰·비밀번호·secret·API key 이름의 헤더/JSON 필드는 가립니다. URL 쿼리는 이름만 남기고 값은 숨깁니다. 나머지 헤더도 허용된 진단용 헤더만 값을 표시합니다. 바이너리·멀티파트·스트리밍 본문은 출력하지 않으며 크기와 Content-Type/Disposition/Range, 제공되는 ETag/Digest 등으로 확인합니다. JSON 로그 캡처는 방향별 16 MiB까지로 제한하고 초과하거나 파싱 불가능한 본문은 콘솔 본문을 생략합니다. 원문 DB 저장과 다운로드는 그대로 유지합니다.

X-LNIS-Request-ID / X-LNIS-Trace-ID 헤더로 양쪽 HTTP 호출을 연결합니다. 같은 요청 처리 중 외부로 호출하면 traceId를 이어갑니다. 별도 비동기 시험 작업은 본문의 시험 ID로도 연결해 확인합니다. API_FAILURE는 실패 종류와 호출 위치를 기록하고, 업무 로그와 수신 원문 기록은 유지합니다. 로그 목적의 전체 응답 버퍼링은 하지 않으며 기존 HTTP 취소·제한 시간·다운로드 바이트를 보존합니다.

Docker 콘솔의 레벨 표시는 `[WARN]`만 굵은 노랑(ANSI 1;33), `[ERROR]`만 빨강(ANSI 31)으로 출력하고 즉시 색상을 복원합니다. 다른 레벨과 메시지 본문은 색칠하지 않습니다.

헬스체크 실패는 매번 WARN 한 줄로 기록하고 예상되는 연결 예외의 스택은 DEBUG에서 확인합니다. 예기치 않은 서버 내부 예외는 ERROR에 마스킹된 원인 체인과 스택을 남깁니다. HTTP 진단 수준은 `LNIS_HTTP_LOG_LEVEL=INFO`(기본)/`DEBUG`로 변경 후 컨테이너를 재생성합니다. 시간 표시는 Asia/Seoul이며 저장/전송 시각은 기존 UTC 계약을 유지합니다.

화면 로그는 POST /lnis/api/v1/dtn/logs/screen만 사용합니다. 과거 독립 AFS 화면 로그 API는 제거했습니다.


### 관리자용 로컬 데이터 관리

`/lnis/data-manager`(기존 `/lnis/data-management`도 지원)는 각 PC의 DTN 시험, 수신 원문, 입력 GRAW, I/Q 파일을 관리한다. DTN 송신·수신 페이지 좌측 최상단의 `data-management-entry` 링크는 `hidden` 기본값이다. 개발자 도구에서 해제해 사용한다. 별도 인증은 없으며 hidden은 접근 통제가 아니다.

관리 API 기준 경로: `/lnis/api/v1/data-management`.
- `GET /summary`: 현재 역할, DB 파일과 입력/IQ 파일 용량, 자료 건수. DB 물리 크기는 삭제 직후 줄어들지 않을 수 있다.
- `GET /items?kind=DTN|RECEIPT|INPUT|IQ&page=0&search=&state=&from=&to=`: 메타데이터만 50건씩 최신순 조회. from 포함, to 미포함(ISO Instant). IQ는 서버 소유 파일 이름만 조회하며 사용자 경로를 받지 않는다.
- `GET /items/{kind}/{id}`: 상태·관련 자료·기존 조회/다운로드 API 연결.
- `POST /pin`: `{item:{kind,id},pinned:true}`. 기존 입력/IQ 삭제 API에도 보관 고정 보호가 적용된다.
- `POST /preview`: `{items:[{kind,id}]}` 최대 500건. 삭제 예정 원문·로그·단독 참조 파일과 제외 사유, 10분 유효한 token 반환.
- `POST /delete`: `{token}`. 실행 직전 재검사하며 진행 중 시험·중지 확인·생성·수집 중에는 409. 다른 시험이 참조하거나 보관 고정된 파일은 보존. 실패는 정리 내역에 남긴다.
- `GET /history`, `POST /history/{id}/retry`: 최근 50건 정리 결과 및 실패 자료의 새 미리보기.
- `GET /settings`, `POST /settings/preview`: `{tests:{enabled,days},receipts:{enabled,days},files:{enabled,days}}`. 최초 모두 disabled, 기본 입력 일수 30일, 허용 1~3650일. 설정 미리보기 후 `POST /settings`에 `{token}`으로 저장한다.
- `POST /cleanup/preview`: 현재 보관 정책의 삭제 예정 목록. 실제 정리는 `/delete`로 확인한다.
- `GET /files/INPUT|IQ/{id}`: 로컬 완료 파일 다운로드.

시험 보관은 종료 갱신 시각, 미연결 원문은 수신 시각, 미사용 파일은 생성 시각(기존 I/Q는 최초 남아 있는 파일 시각)을 기준으로 한다. 10분마다 최대 500건씩 동일 삭제 로직을 사용한다. DTN 시험과 입력 정리는 이 설정을 사용하며 기존 LNIS_COMPLETED_RETENTION/INCOMPLETE_RETENTION으로 시험을 삭제하지 않는다. 내부 통신 이벤트 24시간 및 준비 로그 7일 정리는 유지한다. 운영 연결 설정·인증 값·Agent 등록은 관리 삭제 대상이 아니다.

삭제는 요청과 경합하지 않게 직렬화하고 파일/DB 실패 시 이력을 보존해 재시도한다. 관리 테이블은 정책·고정·삭제 ID·작업 이력을 저장하며 원문을 복사하지 않는다. 삭제 ID는 늦은 재등록·로컬 계산 콜백로 시험이 되살아나는 것을 차단하기 위해 유지한다. 운영 DB 초기화·압축·복원은 제공하지 않는다.


## 1 Epoch 지연 반영 PVT 비교 (LNIS 내부 기능)

GNSS_RAW/AFS_METADATA는 항상 지연 PVT이며 체크박스는 없습니다. I/Q는 기존 RF 추적 시험입니다.

- `GET /lnis/api/v1/dtn/inputs/{id}/delay-epochs`: 유효 Reference가 있는 Epoch 후보.
- `POST /lnis/api/v1/dtn/tests`: `selectedEpoch:{recordIndex,week,towSeconds}` 생략 시 첫 유효 Epoch 선택.
  `comparisonMode` 생략/null/`DELAY`는 지연 시험, RAW/AFS의 `RESTORE`는 거절합니다.
- 송신 `Ttxᵢ=S−Pᵢ/c`, 수신 `P′ᵢ=c×(R−Ttxᵢ)`, GNSS 계산 시각 `t₀+(R−S)`.
  수집 후 시작 전 대기는 제외하고 시작 접수부터 본문 수신 완료까지 포함합니다.
- `/node/peer/dtn/tests/capabilities`의 `delayTransferSupported:true`를 확인합니다. 양쪽 LNIS를 함께 갱신하세요.
  사전 등록은 원본·Reference를 보내지 않습니다.
- `GET /lnis/api/v1/node/peer/dtn/tests/{id}/reference`: 송신 노드 전용, 관리 Bearer 토큰 필수.
  `{testId,payloadSha256,sourceSha256,grawBase64,referencePvt}` 반환.
  GRAW는 시험에 고정한 1 Epoch+선행 레코드이며 전체 수집 파일과 다를 수 있습니다.
- 수신은 독립 계산 후 원본을 조회하고 시험 ID·원본 해시·Epoch·재생성 전송 해시를 검사합니다.
  원본이나 Reference를 수신 PVT 입력에 넣지 않습니다.
- `referenceStatus`: `WAITING`(자동 재시도), `COMPLETE`, `MISMATCH`(자료 불일치), `UNAVAILABLE`(조회·검증 불가).
  일시 오류는 10/30/60초, 이후 60초 간격이며 재시작 후에도 재시도합니다.
  `POST /lnis/api/v1/dtn/tests/{id}/reference/retry`는 수신에서 재조회를 예약하고 202를 반환합니다.
- 보고서 `observations.receivedValues`: 실제 변환 RAW JSON 또는 AFS 복원 관측값.
  `observations.epochs`: 수신 계산 입력. `referenceObservations`: 별도 조회한 원본.
  원문 다운로드에는 별도 조회값을 섞지 않습니다.
- 표의 원본 의사거리는 조회 후 표시, 변환 후 의사거리는 빨간색입니다.
  기준시간 옆 데이터 일치 여부는 의도한 변환·제외를 적용한 전달 데이터 대조 결과입니다.
- `delayEvidence`는 S/R·지연·GNSS 시각·재계산 거리를 보존합니다. 새 수신 계산의 원본 거리·역산 근거는 null입니다.
  실제 가상 송신 시각은 `observations.receivedValues`의 초/fs를 사용합니다.
- `comparison.mode=DELAY`, 판정은 `MEASURED/PARTIAL/INCONCLUSIVE`이며 합격 허용오차는 없습니다.
  `clockResidualSeconds=(수신 Bias−Reference Bias)−delaySeconds`, `clockResidualMeters=c×clockResidualSeconds`.
  시간 잔차의 거리 환산은 위치 오차가 아닙니다.
- 음수 지연은 시계 동기화 문제로 비교 불가입니다. 재조회·재시작으로 최초 수신 시각이나 PVT를 바꾸지 않습니다.
- 송신 로그는 송신 처리·어댑터 접수, 수신 로그는 의사거리·독립 PVT·비교를 각각 기록합니다.

### 송신 접수 이후 로그 역할

송신은 어댑터 접수 이후 `[상대 결과 확인]`으로 수신 완료·시험 완료·실패·시간 초과·취소 상태를 남깁니다.
상대 처리 상세·DTN 어댑터 로그·최종 PVT 수치 로그를 송신 로그에 복제하지 않으며, 비교 결과 저장과 상태 조회는 유지합니다.
기존 송신에 저장된 `[DTN]`, `[송신 최종 요약]`, 상세 `[PVT 비교]`는 송신 로그 조회와 TXT 다운로드에서 제외합니다.
DB 기록은 삭제하지 않으며 `nextSequence`와 `hasMore`는 필터링 전 페이지를 기준으로 하므로 빈 페이지 이후 로그도 계속 조회합니다.


## 18. 여러 시험 송신과 지연 수신 (2026-09-29)

외부 `/transfers` 본문 규격은 유지한다. 시작 요청마다 새 testId를 생성하고 수신 서비스에 사전 등록한다. 송신 준비·어댑터 HTTP 요청이 끝나면 이전 시험의 최종 결과와 관계없이 다음 시험을 시작할 수 있다. 수신 서비스의 계산기가 BUSY여도 사전 등록과 데이터 접수는 가능하다.

- `GET /lnis/api/v1/dtn/config`: `sendBusy`는 로컬 송신 준비·요청 잠금이다. 최종 수신 대기는 이 잠금을 점유하지 않는다.
- 시험 요약의 `sendStatus`: `PREPARING`, `REQUESTING`, `ACCEPTED`, `REJECTED`, `UNKNOWN`. 과거·수신 측 기록에는 null일 수 있다. `ACCEPTED`는 어댑터 접수 확인이며 최종 전달 성공이 아니다.
- `stageStartedAt`: 현재 단계 시작 시각. 진행 로그 갱신으로 작업 제한 시간이 연장되지 않는다.
- `lateReceivedAt`: 종료 확정 뒤 정상 본문이 도착한 최초 시각. `receivedAt` 및 계산 결과와 구분한다.
- `GET /dtn/tests?page=0&state=WAITING_DTN`: 기존 배열 응답을 유지한다. 페이지는 0부터, 크기는 50이며 state 생략 시 전체 시험이다.
- `POST /dtn/tests/{id}/cancel`: 선택 시험에만 적용한다. 수신 측에서도 로컬 시험을 종료할 수 있다. 송신 측 `cancelPending`은 상대 전달 대기이며 어댑터 번들 취소 여부가 아니다.
- 정상 최초 수신 시각을 보존한다. 동일 본문의 재수신은 계산을 반복하지 않고, 다른 본문은 거절 원문으로 보관한다. 검증 실패 후에도 대기 중 시험은 정상 본문을 다시 받을 수 있다.
- 종료 후 정상 본문은 원문 기록 상태 `AFTER_CANCEL`로 보관하고 계산하지 않는다. HTTP 202의 `accepted:false`를 확인해야 한다.
- 송신부는 브라우저와 무관하게 결과를 조회한다. 개별 시험 조회 간격은 최소 10초이고 동시 요청은 최대 2개이며, 많은 시험은 순환 조회로 간격이 길어질 수 있다.
- 수신부 계산은 최초 정상 수신 시각 순서로 한 건씩 실행한다. 전달 지연에는 수신 후 계산 대기·Reference 조회시간이 포함되지 않는다.
- 재기동 시 요청 중인 송신은 UNKNOWN으로 바꾸고 대기를 이어간다. 자동 재전송하지 않는다. 대기 중 시험과 필요한 파일은 보관 정책의 자동 삭제에서 보호한다.
