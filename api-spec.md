# DTN 시험 API

기본 경로: /lnis/api/v1/dtn

## 시험 시작과 결과

| 요청 | 역할 |
|---|---|
| POST /tests | 새 testId로 시험 시작 |
| GET /tests?page=0 | 시험 이력 (페이지당 최대 50건) |
| GET /tests/{id}/report | Reference·수신 PVT·계산 근거·관측값 |
| POST /tests/{id}/cancel | 해당 시험만 종료 |
| GET /tests/waiting-summary | 일괄 종료 대상 스냅샷 |
| POST /tests/cancel-waiting | 스냅샷의 testIds와 asOf로 대기 시험 종료 |
| POST /receive | 수신 어댑터 콜백. Authorization: Bearer <수신 토큰> |
| GET /receipts | 수신 접수 이력 |
| GET /receipts/{id}/body | 검증 전 수신 본문 다운로드 |

대기 시험은 testId별로 관리하며 전송 순서와 수신 순서가 달라도 됩니다.
수신 대기는 무기한이며 실제 준비·계산 작업에는 제한 시간이 있습니다.
종료 후 도착한 본문도 접수 기록으로 보존하지만 시험을 다시 시작하지 않습니다.

## 어댑터 전달 규격

| testType | schemaVersion | format | 내용 |
|---|---:|---|---|
| GNSS_RAW | 2 | LNIS-GRAW-DELAY-v2 | 원본 의사거리를 가상 송신 시각으로 바꾼 1 Epoch |
| AFS_METADATA | 6 | LNIS-AFS-GNSS-v6 | GPS PRN별 750-byte AFS 프레임 |
| IQ_SAMPLE | 3 | LNIS-IQ-FILE-v3 | I/Q 파일 경로·크기·SHA-256, 신호 정보와 비교 Reference |

AFS_METADATA는 기존 API 식별자를 유지합니다. 화면에서는 AFS Frame으로 표시합니다.
RAW·AFS 시험은 원본 GNSS 및 Reference를 어댑터로 보내지 않습니다.
I/Q의 pvtMethod는 AFS_IQ_FRAME_PVT-v3입니다. 전달된 Reference는 비교용이며 복호나 PVT 입력으로 사용하지 않습니다.
GPS L1 C/A만 PVT에 사용합니다. pvtConstellation은 생략하거나 GPS를 지정합니다. 다른 값은 거절합니다.

## 공통 지연 계산

S = 시험 시작 접수 시각, R = 수신 본문 수신 완료 시각입니다.
AFS는 프레임 기준 A=S, I/Q는 생성 당시 기준 A를 사용합니다.

- 프레임 송신 시각: Tᵢ=A−Pᵢ/c
- 시험 시작 기준 보정: T*ᵢ=Tᵢ+(S−A)
- 수신 의사거리: P′ᵢ=c×(R−T*ᵢ)=Pᵢ+c×(R−S)

AFS와 I/Q는 Type 63 / Version 6의 같은 SB 구성을 사용합니다.
SB4에는 가상 송신 시각·기준 시각·원본 Doppler·C/N0·유효성·전리층 정보를 넣습니다.
I/Q도 실제 RF 프레임 복호 후 위 계산을 수행하며 RF 추적 관측값은 trackingEpochs 진단 자료로 분리합니다.
I/Q 파일 생성 후 대기와 수신 후 복호시간은 지연에 합산하지 않습니다.
PC 시계 동기화가 전제이며 음수 지연은 비교 불가로 처리합니다.

## 서비스 간 비교자료

GET /lnis/api/v1/node/peer/dtn/tests/{testId}/reference

상대 서비스 관리 토큰으로 인증합니다. 원본 GNSS·Reference는 계산 후 비교에 사용합니다.
진행 중·수신 대기·종료 확인·비교자료 조회가 남으면 상대 LNIS 서비스 주소 변경을 막습니다.
어댑터 전송 주소와는 별개입니다.

## 이전 파일

이전 시험 결과와 파일은 삭제하지 않습니다.
새 시험은 현재 프레임 규격만 허용하며 이전 I/Q 파일은 REGENERATE로 표시합니다.
기존 파일을 새 규격이라고 이름만 바꾸어 전송하면 안 됩니다.
