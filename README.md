# LNIS 송수신 시험

**통신 지연을 GNSS 의사거리에 반영했을 때, PVT 계산기가 공통 지연을 수신기 시계오차(Clock Bias)로 해석하는지 확인하는 시험입니다.**
DTN/HDTN을 거친 데이터의 수신·복원·일치 여부도 함께 확인합니다.

송신부가 원본 GNSS의 1 Epoch로 Reference PVT를 계산하고, 수신부는 전달받은 정보와 지연시간으로 PVT를 계산해 비교합니다.
공통 지연은 주로 Clock Bias 증가로 나타나며 위치·속도에는 작은 차이가 생길 수 있습니다.
실제 미래 위성 운동이나 실제 위치 정확도를 검증하는 시험은 아닙니다.

## 1. 실행과 설정

송신·수신 PC에 각각 **LNIS 서비스 + 외부 DTN/HDTN 어댑터**를 실행합니다. DB와 공유 파일 폴더는 PC별로 관리합니다.
Java 21 · Spring Boot · H2 · HTML/JavaScript를 사용하며, PVT는 기존 RTKLIB GPS L1 C/A SPP 계산기를 사용합니다.

새 PC에는 build/distributions/lnis-node-linux.zip을 풀고 .env.example을 .env로 복사합니다.
배포 폴더의 **.env**에서 다음 값을 설정합니다.

| 설정 | 용도 |
|---|---|
| LNIS_NODE_ROLE | sender 또는 receiver |
| LNIS_SERVER_PORT | 현재 서비스 포트 |
| LNIS_NODE_BASE_URL / LNIS_NODE_PEER_URL | 본인 / 상대 LNIS 주소 |
| LNIS_NODE_MANAGEMENT_TOKEN | 양쪽 LNIS가 사용하는 동일한 관리 토큰 |
| dtn_adapter | 연결할 어댑터 주소. 예: http://192.168.1.154:8080 |
| LNIS_DTN_SEND_TOKEN / LNIS_DTN_RECEIVE_TOKEN | 해당 어댑터와 맞춘 송신 / 수신 토큰 |
| LNIS_IQ_ENABLED | I/Q 생성 사용 여부 |

~~~sh
# 소스 루트가 아닌 배포 폴더에서 실행
docker compose up -d --build
docker compose ps
~~~

Windows COM을 사용할 때는 배포 폴더의 **START.cmd 또는 start.ps1**로 시작합니다. Docker 기동만으로 Windows COM 중계가 시작되지는 않습니다.
중계 포트는 .env의 LNIS_SERIAL_BRIDGE_PORT, 실제 COM 번호는 화면의 **포트 조회**에서 설정합니다.

- 기본 접속: http://PC주소:포트/ → 설정된 역할의 시험 화면
- 설명 구성도: /dtn-intro
- 상세 절차: [노드 배포](deployment/node/README.md) · [Windows COM](deployment/node/WINDOWS-SERIAL-BRIDGE.md) · [WSL2 USB](deployment/node/WSL2-GNSS.md)

기존 **.env, DB, exchange**는 재배포 시 유지합니다.

## 2. 시험 순서

1. **설정:** 상대 LNIS·어댑터 주소, 시험 유형, DTN/HDTN 경로를 확인합니다. HDTN 설정은 어댑터에 전달할 요청값이며 실제 적용은 어댑터가 담당합니다.
2. **입력:** GNSS를 연결해 **1 Epoch 수집**하거나 .ubx / .graw 파일을 적용합니다. 관측 시점을 선택하고 원본 PVT를 확인합니다.
3. **시각 보정:** 진행 중인 시험을 종료한 뒤 **양쪽 화면에서 각각 ‘시간 맞추기 → 적용’**을 실행합니다. 한쪽에만 GNSS가 있다면 그쪽부터 적용합니다.
4. **전송시험 시작:** 클릭마다 새 testId로 시험을 등록합니다. 어댑터 요청이 끝나면 이전 시험의 최종 수신을 기다리지 않고 다음 시험을 보낼 수 있습니다.
5. **수신 결과:** 데이터 일치 여부와 위치·속도·Clock Bias 차이, 상세 로그를 확인합니다.

**시간 맞추기는 누른 서비스의 시험 시각만 보정합니다.** OS·로그·어댑터 시계는 바뀌지 않으며 재기동하면 보정이 초기화됩니다.
양쪽 시각이 다르면 음수 지연이나 잘못된 지연이 나올 수 있습니다. 표시 자릿수는 동기화 정확도를 의미하지 않습니다.

COM 수집은 최대 120초입니다. 시간 초과 시 남은 관측값을 사용할지 선택할 수 있지만, PVT 계산은 보장되지 않습니다.
유센터에서 같은 COM을 쓰려면 먼저 LNIS에서 **연결 해제**해야 합니다.
UBX는 최대 64 MiB·1000 Epoch, 변환된 GRAW는 최대 1 MiB를 지원합니다.

수신은 등록된 testId와 인증·무결성을 확인하며, 외부 데이터 도착 대기에는 10분 제한이 없습니다.
종료하려면 시험별 **대기 종료 / 계산 중지**, 또는 **대기 전체 종료**를 사용합니다.
이미 어댑터로 보낸 번들을 회수하는 기능은 아닙니다. 종료 후 도착한 데이터는 원문만 보관합니다.

## 3. 전송 방식과 PVT 비교

| 유형 | 어댑터로 전달하는 데이터 | 수신측 처리 |
|---|---|---|
| GNSS RAW | 선택한 1 Epoch의 의사거리를 가상 송신 시각으로 바꾼 JSON | 의사거리 재계산 → PVT → 원본 비교 |
| AFS Frame | 위성별 6,000비트 프레임 목록과 시험·엔진 정보 | 프레임 복원 → 의사거리 재계산 → PVT → 원본 비교 |
| I/Q Sample | BIN 파일 경로·크기·해시 및 생성·기준 정보 | 파일 확인 → 신호 추적·프레임 복호 → PVT 비교 |

RAW·AFS의 원본 GNSS와 Reference는 수신 PVT 계산 **후** LNIS 간 REST로 별도 조회합니다.
Reference는 장비의 NAV-PVT가 아니라 송신 원본 관측값으로 계산한 PVT이며, 수신 계산을 대신하지 않습니다.
I/Q는 별도 신호 추적 시험으로, 파일 전달 지연을 의사거리에 추가하지 않습니다.

~~~text
t₀: 원본 GNSS 관측 시각    S: 시험 시작 시각    R: 수신 본문 완료 시각
Δt = R − S                c = 299,792,458 m/s

송신: 가상 송신 시각 T*ᵢ = S − Pᵢ/c
수신: 의사거리 P′ᵢ = c × (R − T*ᵢ) = Pᵢ + cΔt
      GNSS 계산 시각 = t₀ + Δt
비교: 시간 잔차 = (수신 Clock Bias − Reference Clock Bias) − Δt
~~~

수집 후 시작 전 대기는 제외하고, 시작 이후 준비·어댑터·전송 대기는 포함합니다. 수신 후 계산 대기는 제외합니다.
RAW·AFS의 Doppler·C/N₀·궤도정보는 송신 값을 유지합니다. Doppler가 같아도 계산 위치·위성 방향 등에 따라 속도에 작은 차이가 생길 수 있습니다.

- **데이터 일치:** 의도한 변환·제외를 고려해 전송 대상 데이터를 대조한 결과입니다. PVT 성공 여부와 별개입니다.
- **PVT 결과:** MEASURED 측정 완료 / PARTIAL 부분 비교 / INCONCLUSIVE 비교 불가. 합격 허용오차는 적용하지 않습니다.
- **수신기 정보(송신):** 파일 정보 옆에서 모델·펌웨어·프로토콜·지원 위성군·Epoch 수·관측 구간을 확인합니다. MON-VER 정보는 새 UBX 적용 시 보관하며 과거 입력의 미기록 값은 추정하지 않습니다.
- **관측표:** 기본은 전체 항법정보입니다. 위성 버튼으로 필터하고 작은 **전체 보기** 버튼으로 해제합니다. GPS G04는 PRN 4를 뜻합니다. 관측 수·GPS L1 입력 대상·SF1~3 확인·계산기 실제 사용 수는 서로 구분합니다.
- **계산 상태:** 누르면 부족한 입력이나 실제 실패 이유를 확인합니다. SF1~3 존재만으로 궤도 유효성·계산 성공을 보장하지 않습니다.
- **의사거리 두 열:** 별도 조회한 원본과 수신 재계산값을 비교합니다. 재계산값·보정 송신 시각은 빨간색이며 미전송·미확인 값은 ‘—’입니다.

## 4. AFS 프레임 구조

AFS 지연 시험은 **schemaVersion=5, LNIS-AFS-GNSS-v5**입니다.
GPS L1 관측 위성마다 프레임을 만들며, 원본 의사거리·metadata·Reference를 프레임 전송 JSON에 넣지 않습니다.

~~~text
송신 GNSS → SB2 항법 + SB3 보충 항법 + SB4 가상 송신 시각·관측값
          → DTN/HDTN → 수신 프레임 복원 → P′ 계산 → PVT
송신 원본·Reference ───── 별도 LNIS REST 조회 ─────→ 비교
~~~

| 구간 | 부호화 전 데이터 | 물리 프레임 크기 | 역할 |
|---|---|---:|---|
| 동기 | 고정 패턴 | 68 bits | 프레임 동기 |
| SB1 | FID·TOI → BCH | 52 bits | 기존 프레임 식별·시각 |
| SB2 | 1,176 + CRC 24 → LDPC·천공 | 2,400 bits | 기존 항법정보 |
| SB3 | 846 + CRC 24 → LDPC·천공 | 1,740 bits | LNIS 보충 항법정보 |
| SB4 | 846 + CRC 24 → LDPC·천공 | 1,740 bits | LNIS 관측·시험 시각 |
| **합계** | | **6,000 bits = 750 bytes** | |

~~~text
SB2 2400 + SB3 1740 + SB4 1740 → 5880비트 인터리빙(98×60)

최종 프레임: [동기 68][SB1 BCH 52][인터리빙된 SB2·SB3·SB4 5880]
~~~

SB2·SB3·SB4는 최종 비트열에 단순히 연속 배치되지 않습니다.
물리 크기·오류 정정·인터리빙은 기존 SIM/PocketSDR를 사용합니다.
SB3·SB4 내용은 **LNIS 시험용 확장**이며 공식 메시지 할당을 의미하지 않습니다.
물리 구조 근거: [NASA LSIS AFS Volume A v1.0](https://www.nasa.gov/wp-content/uploads/2025/02/lunanet-signal-in-space-recommended-standard-augmented-forward-signal-vol-a.pdf).

| LNIS 확장 | 846비트 데이터 영역 구성 |
|---|---|
| 공통 헤더 | type 6 + version 8 + block 3 + epochIndex 32 + PRN 8 = 57 bits |
| SB3 | 헤더 57 + 항법 존재 1 + 보충 LNAV 459 + 미사용 329 |
| SB4 | 헤더 57 + 관측 순서 8 + week 16 + TOW 64 + 가상 송신 시각 114 + Doppler 32 + C/N₀ 8 + 추적 상태 8 + 전리층 존재 1 + 항법 PRN 8 + 전리층 페이지 240 + 시험 시작 시각 114 + 미사용 176 |

시각 114비트는 Unix 정수 초 64 + 초 미만 femtosecond 50입니다. 표현 정밀도이며 시계 정확도가 아닙니다.
미사용 영역은 010101…로 채웁니다. GPS 항법 SF1~5와 AFS SB1~4는 서로 다른 구분입니다.

I/Q는 같은 물리 부호화기를 쓰되 기존 version=2 관측 형식과 원본 의사거리를 유지합니다(SB4 사용 506비트).
I/Q 형식은 LNIS-IQ-FILE-v2, 계산 방식은 AFS_IQ_FRAME_PVT-v2입니다.

## 5. 문제 확인과 로그

| 증상 | 먼저 확인할 사항 |
|---|---|
| 음수 지연 / PVT 비교 불가 | 양쪽 시험 시각 보정, 실제 PVT 실패 메시지, 유효 관측·항법정보 |
| 원본 비교 대기 / 조회 실패 | 상대 LNIS 주소·관리 토큰·송신 시험 기록 |
| 401 / 403 | 해당 API의 어댑터 토큰 또는 LNIS 관리 토큰 |
| 연결 실패 / 수신 대기 | 어댑터 주소·포트, 양쪽 서비스, 라우터 로그·번들 TTL |
| COM 연결 실패 | 유센터 등 다른 프로그램의 포트 점유, COM 번호·속도, 중계 실행 여부 |

~~~sh
# 각 배포 폴더에서 최근 100줄부터 확인
docker compose logs --tail 100 -f node
~~~

시험 상세 로그와 실제 송수신 JSON은 INFO, 정상 조회·폴링과 추가 HTTP 정보는 DEBUG입니다. WARN/ERROR는 유지합니다.
로그는 한국 시간이며, testId·requestId로 요청을 추적합니다. IN/OUT, URL·매핑·상대 IP·포트도 기록합니다.
추가 진단은 .env의 LNIS_HTTP_LOG_LEVEL=DEBUG를 적용한 뒤 재기동하고, 완료 후 INFO로 복원합니다.
기본 Compose의 Docker 로그는 **100m × 3개**로 순환하며, DB 보관 정책과는 별개입니다.

## 6. 개발·유지보수

~~~powershell
.\gradlew.bat nativeBuild
.\gradlew.bat check bootJar -PnativeCandidate=build/native-pvt
.\gradlew.bat linuxNodeDistZip
~~~

- 실행 파일: build/libs/lnis.jar / 배포 묶음: build/distributions/lnis-node-linux.zip
- 설정: src/main/resources/application.yml / PC별 설정: 배포 폴더 .env
- 백엔드: server/gnss 입력, server/afs 프레임, server/pvt 계산, server/iq I/Q, server/dtn 시험, server/node 노드
- 화면: src/main/resources/static/assets/dtn / 설명 구성도: src/main/resources/static/dtn-intro.html
- 안내 페이지 동기화: gradlew.bat syncIntroDocs → docs/index.html
- 기존 운영 배포: scripts/deploy-nodes.ps1 — 설정·DB 유지, 진행 중 작업 확인, 재기동·상태 확인
- 네이티브 원본·수정 정책과 상세 빌드: [native/README.md](native/README.md)

일반 빌드는 운영 서비스를 재기동하지 않습니다. 합성·개발용 REST 시험 통과와 실제 GNSS·DTN/HDTN 장비 검증은 구분합니다.
