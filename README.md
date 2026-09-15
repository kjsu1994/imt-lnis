# LNIS 송수신 시험

Java 21 · Spring Boot · H2 · HTML/JavaScript. 기존 AFS 코덱과 GPS L1 지구 PVT 계산기를 재사용합니다.

## 실행 구조

- **송신 PC:** LNIS Sender + 송신 DTN/HDTN 어댑터 + 로컬 공유 파일 폴더.
- **수신 PC:** LNIS Receiver + 수신 DTN/HDTN 어댑터 + 별도 로컬 공유 파일 폴더.
- 각 PC에서 LNIS는 한 JVM으로 실행합니다. DB와 파일은 서로 공유하지 않습니다.
- 개발 PC에서는 Docker Desktop의 8090/8091로 두 역할을 구분합니다. 운영은 WSL2 배포판 내부 Linux Docker Engine입니다.
- 어댑터는 별도 개발 프로그램입니다. LNIS가 DTN/HDTN 자체를 구현하지 않습니다.

## 실행·설정

애플리케이션 설정은 `src/main/resources/application.yml` 하나에서 관리합니다. 내부의 `spring.config.activate.on-profile` 조건으로 공통·서버·통합 노드·별도 Agent 설정을 구분하며, PC별 주소·포트·토큰은 기존처럼 `.env`로 지정합니다.

독립 노드 배포 폴더에서 `.env`의 역할·포트·본인/상대 URL·관리 토큰을 설정하고 실행합니다.

```sh
docker compose up -d --build
docker compose ps
```

주요 설정: `LNIS_NODE_ROLE`, `LNIS_SERVER_PORT`, `LNIS_NODE_BASE_URL`, `LNIS_NODE_PEER_URL`, `LNIS_NODE_MANAGEMENT_TOKEN`,
`dtn_adapter`, `LNIS_DTN_SEND_TOKEN`, `LNIS_DTN_RECEIVE_TOKEN`.
관리 토큰은 양쪽 동일하게, 어댑터 토큰은 해당 연결 상대와 맞춥니다. 기존 DB·토큰을 임의 삭제하지 마세요.

- AFS 화면: `/lnis/afstest/sender`, `/lnis/afstest/receiver`
- DTN 화면: `/lnis/dtntest/sender`, `/lnis/dtntest/receiver`
- [운영 USB/WSL2 연결](deployment/node/WSL2-GNSS.md)
- [어댑터 개발자 공유 계약 — API-SPEC 맨 아래 15장 전체](API-SPEC.md#adapter-contract)

## 시험

DTN의 GNSS 수집 데이터 화면은 관측값(RAWX)과 항법정보(SFRBX)를 별도 표로 표시합니다. 항법 메시지는 중복을 포함해 보존하며, 펼침 메뉴에서 저장된 전체 필드를 확인할 수 있습니다. 현재 GRAW는 RAWX/SFRBX와 수집 메타데이터를 저장하며 NAV-PVT·NMEA 등 수신기의 다른 출력은 포함하지 않습니다.

1. 송신 화면 우측 상단 **설정**에서 시험 종류·DTN/HDTN 경로·연결 주소·COM/GRAW 입력을 선택합니다. GRAW 파일 적용과 저장된 I/Q 선택도 설정에서 합니다.
2. **시험 화면으로** 돌아와 COM 수집·I/Q 생성·전송을 실행하고 관측값·송신 지구 PVT를 확인합니다. 화면 전환은 입력과 진행 상태를 유지하며, 처리 중 설정은 읽기 전용입니다. 선택값은 매 REST JSON에 포함됩니다.
3. 수신 결과와 JSON 원문을 확인합니다.

| 시험 | 전송 내용 | 검증 |
|---|---|---|
| GNSS RAW | 원본 GRAW 바이트의 Base64 | 입력 무결성·송수신 지구 PVT |
| AFS + Metadata | GRAW를 담은 AFS 프레임 | 복원 무결성·송수신 지구 PVT |
| I/Q Sample | 90초 BIN의 공유 경로·크기·해시 | 수신 파일 크기·SHA-256 |

PVT는 지구 ECEF GPS L1 SPP입니다. 송수신 일치는 계산 재현성 검증이며 실제 위치 정확도 보증이 아닙니다.
수신 화면은 전송 JSON의 송신 기준 PVT와 독립 계산한 수신 PVT를 좌우로 비교하고, 일치 여부와 차이를 표시합니다. 기준값이 없는 과거 시험은 비교 불가로 표시합니다. 송신 화면에는 기준 PVT만 표시하며 서버의 비교 판정은 유지합니다.

송수신 로그의 **상세 로그**에서 처리 단계·수량·검증 결과를 확인하고 시험 기록을 선택해 TXT로 다운로드할 수 있습니다. 각 PC의 처리 로그만 저장합니다. ‘화면 지우기’는 저장 기록을 삭제하지 않으며, 시험에 연결하지 않은 준비 로그는 7일 후 정리합니다.
관측값만 있는 파일은 전달 가능하지만 PVT는 판정 불가입니다.

I/Q는 송신에서 `LNIS_IQ_ENABLED=true`로 활성화합니다. 양쪽 LNIS와 **각자의 로컬 어댑터**가 공유 폴더를 `/exchange`에 마운트해야 합니다.
COM/GRAW 입력 적용 → 지구 PVT 계산 → 90초 I/Q 생성 → 전송 순서입니다. GPS PRN별 LNAV를 원본 SB2에 넣고 각 프레임을 반복·합산합니다. AFS 변조·부호화는 원본을 사용하며, 원본의 공유 위상/난수 상태 충돌 방지를 위해 생성은 단일 스레드로 실행합니다. 초기 PVT의 등속 운동을 가정한 시험 신호이며 실측 RF 또는 I/Q 복조 PVT 검증은 아닙니다.
기존 LANS AFS 시뮬레이터의 90초·12 MHz 출력은 2.16 GB입니다. REST로 파일 본문을 보내지 않습니다.
빌드 사본에서 원본의 0.1초 부족한 출력 루프를 보정하며, 실제 바이트 수로 90초를 검증합니다. 원본 파일은 수정하지 않습니다.
수신 어댑터는 수신 PC 폴더에 BIN을 완성한 다음 원래 JSON으로 콜백합니다. I/Q에서 지구 PVT를 복원하지 않습니다.

## 개발용 눈으로 확인

운영 ZIP에서는 개발용 중계·예제를 제외합니다. 개발 시만 `deployment/node/docker-compose.dev.yml`, `dev-relay.mjs`와 생성한 `examples`를 배포 폴더에 둡니다.
두 노드가 사용할 `lnis-development` Docker 네트워크를 먼저 생성합니다.
개발 중계기는 REST 전달과 두 로컬 폴더 사이 파일 복사만 수행하며 **실제 DTN/HDTN 시험이 아닙니다.**

- Windows 개발: `COMPOSE_FILE=docker-compose.yml;docker-compose.dev.yml`
- Linux 개발: `COMPOSE_FILE=docker-compose.yml:docker-compose.dev.yml`
- 송신에만 `COMPOSE_PROFILES=relay`, `LNIS_DEV_EXAMPLES=true`.
- `LNIS_DEV_RECEIVE_TOKEN`은 수신 LNIS의 수신 토큰, `LNIS_DTN_SEND_TOKEN`은 개발 중계 접수 토큰으로 설정합니다.
- 중계 기본 콜백은 개발용 수신 8091입니다. 운영 주소·포트 계약이 아닙니다.

송신 설정의 **합성 PVT 수집 재생 · 개발용**은 기본 `hidden`입니다. 개발자 도구에서 숨김을 해제하고 누르면 유효한 PVT 입력을 재생합니다.
같은 입력으로 RAW와 AFS를 전송해 수신 화면과 비교하세요.
이는 F9T 실측/COM 수집이 아닙니다. 기존 F9T 공개 예제는 항법정보가 없어 PVT 계산용이 아닙니다.

## 빌드·검증

```powershell
.\gradlew.bat test webTest bootJar
.\gradlew.bat syntheticGraw
```

- JAR: `build/libs/lnis.jar`
- 합성 입력·예상 PVT: `build/dtn-example/synthetic-earth-pvt.graw`, 동일 이름 JSON
- Linux 코덱: 기존 `native/build-linux.ps1`
- I/Q 생성기: `native/build-iq.ps1 -OpenSourceDirectory <오픈소스 폴더>`
- 배포 ZIP: `gradlew.bat linuxNodeDistZip -PlinuxNativeStage=<코덱 빌드 사본>`

일반 `build`는 운영 폴더를 변경하지 않습니다. 배포는 명시적으로 수행합니다.
실제 F9T·운영 WSL2 USB·실제 DTN/HDTN 연동은 합성 데이터 회귀시험과 별도로 확인해야 합니다.
기존 중앙 서버/Windows Agent 모드는 유지하며 설정은 [기존 배포 문서](deployment/compose/README.md)를 참고하세요.


## 유지보수할 때 찾을 위치

| 영역 | 위치 | 역할 |
|---|---|---|
| 실행 모드 | `server/bootstrap` | 중앙 서버·독립 노드·별도 Agent 시작 |
| 서버 기능 | `server/central` | 기존 Controller → Service → Repository 구조 |
| 로컬 실행기 | `server/agent` | 수집·송수신·코덱 실행 |
| 공통 계약 | `server/shared` | 모델·명령·코덱 계약 |
| 화면 공통 | `static/assets/common` | 기본 CSS, HTTP 요청, 노드 연결 |
| AFS 화면 | `static/assets/afs` | 송수신 화면, 결과 표시, 프레임·로그 |
| DTN 화면 | `static/assets/dtn` | 송수신 화면, 어댑터 상태, 관측값·원문·로그 |

화면 HTML 4개는 `static` 바로 아래에 둡니다. 기존 `/lnis/assets/*.js`·CSS 주소는 새 위치로 리다이렉트하므로 캐시된 HTML에서도 파일을 찾을 수 있습니다.
`common/api.js`는 기존 import 진입점이며, JSON 응답 처리는 `common/http.js`, AFS 결과 표시는 `afs/result-presentation.js`에서 관리합니다. 빈 응답·404·오류 문구 정책은 호출 화면별로 유지하며 바이너리 다운로드와 WebSocket은 별도 처리합니다.
DTN 스타일은 기존 적용 순서를 유지한 `dtn/dtn-ui.css` 하나에 모았습니다. 개발용 합성 재생 영역의 `hidden`과 `/clear`의 화면 초기화 동작은 유지합니다.

## 기존 WSL 노드에 JAR 갱신

저장소에서 다음 명령을 실행합니다. 새 설치·네이티브 라이브러리 교체는 기존 배포 ZIP 절차를 사용합니다.

```powershell
.\gradlew.bat check bootJar
.\scripts\deploy-nodes.ps1 -ValidateOnly
.\scripts\deploy-nodes.ps1
```

기본 대상은 `C:\lnis-compose`와 `C:\lnis-compose-리시버`입니다. 하나만 갱신하려면 `-TargetDirectories 'C:\lnis-compose'`를 지정하고, WSL 배포판은 `-Distribution Ubuntu`로 선택합니다. 진행 중인 시험·수집을 종료한 뒤 실행하세요.

스크립트는 기존 `node` 서비스의 이미지·DB 마운트를 확인하고 JAR의 SHA-256과 필수 ZIP 항목을 검증한 다음 교체합니다. 각 노드의 `backups/날짜-시간`에 이전 JAR·설정과 정지 상태의 DB를 보관하고, 이미지를 빌드한 뒤 노드를 순서대로 재기동하여 Docker readiness를 기다립니다. Compose에 `build`가 없는 수신 노드도 지원합니다. 기존 `.env`, Compose, Dockerfile, 네이티브 파일은 덮어쓰지 않습니다.
실패한 노드는 이전 JAR·이미지로 복구를 시도합니다. 앞서 성공한 노드는 새 버전을 유지하며 DB는 자동으로 과거 상태로 되돌리지 않습니다. 기존 중앙 서버/Windows Agent용 배포와 개발 중계·USB용 Compose 추가 파일은 각 실행 방식에 필요하므로 유지합니다.
