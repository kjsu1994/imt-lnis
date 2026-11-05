# DTN 시험 API

기본 경로: /lnis/api/v1/dtn

## 시험 시작과 결과

| 요청 | 역할 |
|---|---|
| POST /tests | 새 testId로 시험 시작 |
| GET /tests?page=0 | 시험 이력 (페이지당 최대 50건, 생성 시각·testId 내림차순) |
| GET /iq | I/Q 작업·저장 목록 (최대 50건, 생성 중 작업 우선·나머지는 최근 갱신순) |
| GET /tests/{id}/report | Reference·수신 PVT·계산 근거·관측값 |
| POST /tests/{id}/cancel | 해당 시험만 종료 |
| GET /tests/waiting-summary | 일괄 종료 대상 스냅샷 |
| POST /tests/cancel-waiting | 스냅샷의 testIds와 asOf로 대기 시험 종료 |
| POST /receive | 수신 어댑터 콜백. Authorization: Bearer <수신 토큰> |
| GET /receipts | 수신 접수 이력 |
| GET /receipts/{id}/body | 검증 전 수신 본문 다운로드 |

대기 시험은 testId별로 관리하며 전송 순서와 수신 순서가 달라도 됩니다.
수신 대기는 무기한이며 실제 준비·계산 작업에는 제한 시간이 있습니다. 장시간 지연 시 GNSS 항법정보의 선택 조건이나 계산 수렴 조건에 따라 수신 성공과 별개로 PVT 비교가 불가능할 수 있습니다.
종료 후 도착한 본문도 접수 기록으로 보존하지만 시험을 다시 시작하지 않습니다.

시험 목록의 응답 필드는 유지하며, 송수신 원문·관측값 등 목록에 불필요한 대용량 컬럼은 조회하지 않습니다. I/Q 목록은 저장 정보 파일의 수정 시각으로 재기동 후에도 순서를 유지합니다. 목록의 50건 제한은 파일 삭제를 의미하지 않습니다.

## 어댑터 전달 규격

| testType | schemaVersion | format | 내용 |
|---|---:|---|---|
| GNSS_RAW | 1 | LNIS-GRAW-DELAY-v1 | 원본 의사거리를 가상 송신 시각으로 바꾼 1 Epoch |
| AFS_METADATA | 1 | LNIS-AFS-GNSS-v1 | GPS PRN별 750-byte AFS 프레임 |
| IQ_SAMPLE | 1 | LNIS-IQ-FILE-v1 | I/Q 파일 경로·크기·SHA-256, 신호 정보와 비교 Reference |

AFS_METADATA는 기존 API 식별자를 유지합니다. 화면에서는 AFS Frame으로 표시합니다.
RAW·AFS 시험은 원본 GNSS 및 Reference를 어댑터로 보내지 않습니다.
새 I/Q의 pvtMethod는 `AFS_IQ_RF_DELAY-v1`입니다. 전달된 Reference는 별도 참고자료이며 복호나 RF PVT 입력으로 사용하지 않습니다.
GPS L1 C/A만 PVT에 사용합니다. pvtConstellation은 생략하거나 GPS를 지정합니다. 다른 값은 거절합니다.

## 공통 지연 계산

S = 시험 시작 접수 시각, R = 수신 본문 수신 완료 시각입니다.
AFS는 프레임 기준 A=S를 사용합니다. I/Q 프레임은 생성 기준 A를 보관하지만 새 RF 계산은 아래 별도 경로를 사용합니다.

- 프레임 송신 시각: Tᵢ=A−Pᵢ/c
- 시험 시작 기준 보정: T*ᵢ=Tᵢ+(S−A)
- 수신 의사거리: P′ᵢ=c×(R−T*ᵢ)=Pᵢ+c×(R−S)

AFS와 I/Q는 Type 63 / Version 1의 같은 SB 구성을 사용합니다.
미사용 영역은 SB2: 1010…, SB3·SB4: 0101…로 채웁니다. SB2 시작값은 공식 LSIS-300의 spare bit 규칙(0부터 시작)과 다릅니다.
SB4에는 가상 송신 시각·기준 시각·원본 Doppler·C/N0·유효성·전리층 정보를 넣습니다. 시각은 정수 초와 소수부로 분리하며, 소수부는 초의 10⁻¹⁵ 단위 정수입니다. 저장 정밀도는 PC 시계나 GNSS 시각의 정확도를 뜻하지 않습니다.
새 I/Q 파형의 시작 의사거리·Doppler는 SB4와 같은 기준값에 맞추며 이후 기존 궤도·이동 변화를 유지합니다. 수신부는 CRC 통과 프레임의 항법정보와 실제 RF 관측값으로 계산 가능한 첫 RF Epoch를 선택합니다.
RF 기준 PVT와 `P′=P_RF+c×(R−S)`·`t′=t_RF+(R−S)`를 적용한 PVT를 비교합니다. 두 계산의 Doppler·C/N0·항법정보는 동일합니다. RF 관측 부족·계산 실패 시 SB4 관측값으로 대체하지 않습니다.
I/Q 파일 생성 후 대기와 수신 후 복호시간은 지연에 합산하지 않습니다.
송신 시험 시작 시 파일 존재·크기·형식을 확인하고 생성 시 SHA-256을 재사용합니다. 송신 파일의 전체 해시는 다시 계산하지 않습니다.
수신부는 복호 전 전체 SHA-256을 한 번 검증하며 진행률·읽은 용량·경과 시간·처리속도를 로그로 표시합니다. 복호 후에는 파일 크기·수정시각·파일 식별값만 확인하며 변경이 감지되면 실패 처리합니다. 처리 중 공유파일을 변경하지 않는 운영 조건이며, 같은 크기·수정시각으로 내용을 변경한 경우까지 검출하는 방식은 아닙니다.
새 I/Q 보고서의 `observations.source`는 `IQ_RF`이며 선택한 RF 관측·변환 의사거리·복원 항법정보를 제공합니다. `selectedElapsedSeconds`는 선택된 파일 내 시점이며 `trackingEpochs`는 전체 RF 추적값입니다. 과거 `IQ_FRAME` 결과의 의미는 유지합니다. `referenceObservations`는 I/Q에서 반환하지 않으며 Reference PVT·비교 결과는 유지합니다. 관측값 조회는 PVT Epoch 개수와 독립적입니다.
송신 I/Q 보고서의 `observations`는 생성 당시 저장한 원본 1 Epoch입니다. `referencePvt`는 생성에 사용한 기준 PVT입니다. 새로 보관하는 원본 GNSS는 외부 어댑터에 추가하지 않으며 기존 Reference PVT 전송 규격은 유지합니다. 원본 미보관 과거 이력의 `observations`는 비어 있을 수 있습니다. 새 수신 관측표는 선택 RF Epoch의 지연 미적용·적용 의사거리를 표시합니다. 전체 RF 추적값은 보고서 JSON에도 보관합니다.
새 보고서의 `comparisonBasis`는 `RF_BASELINE`이며 `rfBaselinePvt`가 지연 미적용 RF 해입니다. `referencePvt`는 송신 원본, `receivedPvt`는 지연 반영 RF 해로 유지합니다. RF 기준 해는 `fileResult.rfBaselinePvt`에도 보관되어 서비스 간 결과 조회에 포함됩니다.
화면의 `data-integrity`는 `fileResult.verdict`와 `comparison.verdict`를 구분해 함께 표시합니다. 파일 크기·SHA-256 불일치는 `fileResult.verdict=FAIL`로 기록하며, 복호 실패만으로 파일 불일치를 판정하지 않습니다. 지연 시험의 `MEASURED`는 PVT 차이 측정 완료이며 원본과 같은 값이라는 뜻은 아닙니다.
생성 후 전체 해시 계산 중에는 I/Q 상태가 GENERATING이며 phase=VERIFYING과 verificationBytes·verificationTotalBytes·verificationElapsedMillis를 제공합니다.
PC 시계 동기화가 전제이며 음수 지연은 비교 불가로 처리합니다.

## 서비스 간 시험 등록 정보

내부 관리 채널의 `POST /lnis/api/v1/node/peer/dtn/tests`는 `testType`, `senderMode`, `receiverMode`를 함께 등록합니다. 이전 호출과의 호환을 위해 세 필드는 선택값이며, 누락 시 AFS로 간주하지 않습니다. 허용값은 각각 `GNSS_RAW|AFS_METADATA|IQ_SAMPLE`, `DTN|HDTN`, `DTN|HDTN`입니다.

`GET /lnis/api/v1/node/peer/dtn/tests/{testId}/registration`은 송신 노드 전용이며 관리 토큰 인증이 필요합니다. 시험 유형·경로·참여 노드·시각·Epoch·전송 해시를 반환하며 GNSS 원문·프레임·I/Q 파일·Reference PVT는 포함하지 않습니다.

수신 노드는 기동 시 한 번, 유형·경로가 누락된 기존 기록을 위 API로 확인합니다. 시험 ID·참여 노드·전송 해시·비교 조건이 같을 때 누락된 세 필드만 채우며 시험 상태·수정 시각·원문·결과는 유지합니다. 연결 실패나 불일치 기록은 그대로 두고, 다음 기동 때 다시 확인합니다. 외부 어댑터 전달 규격은 변경하지 않습니다.

## 서비스 간 비교자료

GET /lnis/api/v1/node/peer/dtn/tests/{testId}/reference

상대 서비스 관리 토큰으로 인증합니다. 원본 GNSS·Reference는 계산 후 비교에 사용합니다.
진행 중·수신 대기·종료 확인·비교자료 조회가 남으면 상대 LNIS 서비스 주소 변경을 막습니다.
어댑터 전송 주소와는 별개입니다.

## 이전 파일

규격 변경이나 서비스 업데이트만으로 이전 시험 결과와 파일을 자동 삭제하지 않습니다. 관리 화면의 명시적 삭제·초기화는 별도 동작입니다.
새 I/Q 시험은 RF 기준값으로 생성한 파일만 허용하며 이전 파일은 REGENERATE로 표시합니다. 과거 결과와 이미 등록된 이전 방식의 수신 대기 시험은 보존합니다.
기존 파일을 새 규격이라고 이름만 바꾸어 전송하면 안 됩니다.


## 데이터 관리

기준 경로: `/lnis/api/v1/data-management`. 현재 서비스에만 적용하며 어댑터 전송 규격은 변경하지 않습니다.

| 메서드·경로 | 요청·응답 |
|---|---|
| `GET /summary` | `counts`, `receiptBytes`, `inputBytes`, `iqBytes`, `databaseBytes`, `freeBytes`, `role` |
| `POST /selection/preview` | `{kind, search, state, from, to}`. 페이지 제한 없이 조회 조건에 해당하는 삭제 가능 자료 확인 |
| `POST /preview` | 기존 `{items:[{kind,id}]}`, 1~500건 개별 선택 |
| `POST /delete` | 기존 `{token}`. 보호 조건 재검사 후 실행 |
| `POST /reset/preview` | `{mode:"TRIALS"}` 또는 `{mode:"SERVICE"}` |
| `POST /reset/waiting/close` | `{token}`. 확인 목록의 현재 서비스 수신 대기만 종료. 상대 서비스 호출 없음 |
| `POST /reset/execute` | `{token, confirmation}`. 문구는 각각 `시험 데이터 초기화`, `서비스 초기화` |
| `GET /reset/status` | 최근 초기화 상태. 이력이 없으면 빈 응답 |
| `POST /reset/retry` | `{token, confirmation}`. 실패·중단된 작업의 남은 항목 재실행 |

삭제 미리보기는 기존 `items` 외에 `fileBytes`(삭제 파일 예상 용량), `counts`(연관 자료 포함), `excluded`(전체 선택의 제외 항목·사유)를 반환합니다. 확인은 10분간 유효하며 설정 저장 확인은 삭제를 실행하지 않습니다. 자동 정리는 기존 회당 최대 500건을 유지합니다. 전체 선택 확인에는 querySelection=true를 표시하며, 같은 일괄 처리에서 공유가 해제된 파일이라도 미리 확인한 연관 자료에 없으면 유지합니다.

초기화 응답은 `token`, `mode`, `role`, `state`, `counts`, `fileBytes`, `retainedFiles`, `blocked`, `waiting`, `completed`, `total`, `message`, `updatedAt`, `expiresAt`을 포함합니다. 상태는 `PREVIEW|RUNNING|FAILED|COMPLETED`입니다. 진행 수는 파일·폴더 정리와 최종 DB 처리 단계 기준입니다. 재기동으로 중단된 작업은 `FAILED`로 표시하고 재실행을 요구합니다. 만료·대상 변경·진행 작업·초기화 처리 중 충돌은 HTTP 409, 잘못된 확인 문구는 HTTP 400입니다.

`TRIALS`는 시험·결과·원문·로그 및 이전 독립 AFS 보관 이력을 정리하고 입력·I/Q·프리셋·연결·보관 설정은 유지합니다. `SERVICE`는 소유 확인된 입력·I/Q 작업 파일과 프리셋도 정리하며 사용자 설정을 배포 기본값으로 복원합니다. 보관 고정 자료도 포함됩니다. 실행 파일·환경변수·Compose·인증 정보·배포 백업·운영 로그는 유지하며 공유·소유 미확인 I/Q는 `retainedFiles`로 표시하고 제외합니다.

초기화 중 새 시험·수집·수신을 거절하며 상태 조회와 재실행은 허용합니다. 삭제된 시험 ID는 재등록·지연 수신 방지를 위해 유지합니다. 완료 후 `X-LNIS-Data-Revision: <UUID>:TRIALS|SERVICE` 응답 헤더로 브라우저 갱신을 알립니다. `SERVICE`는 해당 출처의 `lnis.*` 화면 설정만 지웁니다. 변경 식별자·저장소 소유 식별자·최근 수행 정보는 초기화 후에도 보관합니다.


## COM 수집과 UBX 다운로드

아래 경로는 서비스 내부 API이며 외부 어댑터 전달 규격과 별개입니다.

| 요청 | 동작 |
|---|---|
| POST /lnis/api/v1/captures | 기존 수집 요청에 epochCount(정수 1~50) 추가. 화면 기본값 10 |
| GET /lnis/api/v1/inputs/{id} | 완료 상태, ubxAvailable, captureProgress 조회 |
| GET /lnis/api/v1/captures/{id}/ubx | 완료된 수집의 UBX 다운로드. 파일이 없거나 미완료이면 404 |

epochCount를 지정하면 위치·속도 PVT가 모두 유효한 고유 Week/TOW만 집계합니다. 최대 120초·GRAW/UBX 각 1 MiB이며 목표 미달은 완료 처리하지 않습니다. 생략한 기존 1 Epoch 호출은 기존 정책을 유지합니다.

captureProgress는 stage·message·counters를 제공하며 counters의 validEpochs·targetEpochs로 진행률을 표시합니다. RAWX·항법정보 수신 계수도 함께 전달합니다. 수집 중에는 폴링과 기존 WebSocket으로 확인할 수 있습니다.

UBX에는 선택된 RAWX와 선행·중간 SFRBX, 확보한 MON-VER를 보관합니다. 다운로드는 선택사항입니다. 완료된 입력은 기존 Epoch 선택·시험 기능에서 바로 사용합니다.

## RAW·AFS 누적 위치 편차

내부 사전 등록과 시험 요약에 선택 필드 pvtHistoryKey(64자리 HEX)를 추가합니다. 같은 입력 ID·선택 Epoch(recordIndex/Week/TOW)·시험 유형·참여 노드·Reference 위치를 묶습니다. 경로·어댑터 설정·testId·시험 시작 시각은 제외합니다. 외부 어댑터 JSON과 AFS/I/Q 규격은 변경하지 않습니다.

GET /lnis/api/v1/dtn/tests/{testId}/pvt-history는 선택 시험과 같은 묶음의 저장 이력을 시작 시각·testId 순으로 반환합니다. 일반 시험 목록의 50건 페이지 제한을 적용하지 않습니다.

| 필드 | 의미 |
|---|---|
| historyKey / testType / selectedEpoch | 묶음과 관측 시점 |
| referenceEcefMeters | 공통 Reference ECEF(m), 미확보 시 null |
| total / waiting / unavailable | 등록·대기·점 표시 불가 건수 |
| trials | 시험별 testId·state·senderMode·receiverMode·startedAt·receivedAt·delaySeconds·clockWarning·availability·ecefMeters |

availability는 AVAILABLE / WAITING / FAILED / CANCELLED / UNAVAILABLE / REFERENCE_MISMATCH입니다. ecefMeters는 위치 비교가 가능한 시험에만 제공합니다. 속도 무효만으로 위치 점을 제외하지 않습니다. 실패·중지·기준 불일치·유효하지 않은 위치는 점으로 표시하지 않습니다. 원문·관측값·설정·상세 로그는 포함하지 않으며 선택한 시험의 기존 API에서 조회합니다.

묶음이 없는 이전 시험과 I/Q는 historyKey=null, 빈 trials를 반환합니다. 과거 시험을 자동으로 묶지 않습니다. 기존 testId 등록의 묶음 값을 변경하면 HTTP 409이며, 신규 등록의 잘못된 형식·유형은 HTTP 400입니다. 같은 입력·Epoch·유형으로 돌아오면 기존 묶음을 복원합니다. 자료 삭제·보관 정리 후에는 남은 이력만 반환합니다.
