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

1. COM 수집 또는 GRAW 파일 적용으로 관측값·송신 지구 PVT를 확인합니다.
2. 시험 종류와 DTN/HDTN 경로를 선택하고 전송합니다. 선택값은 매 REST JSON에 포함됩니다.
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

송신 화면의 **합성 PVT 수집 재생 · 개발용**을 누르면 유효한 PVT 입력을 재생합니다.
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
