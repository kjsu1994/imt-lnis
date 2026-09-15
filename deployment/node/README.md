# Linux 독립 노드 실행

송신·수신 **각각의 Linux x86-64 PC**에 동일한 묶음을 풉니다. Windows 중앙 서버용 묶음과 별개이며 DB를 공유하지 않습니다.

1. `.env.example`을 `.env`로 복사합니다.
2. 현재 역할(`sender`/`receiver`), 현재/상대 PC 주소, 양쪽 공통 관리 토큰을 입력합니다. 수신 PC에는 별도의 DTN 수신 토큰도 입력합니다.
3. `docker compose up -d --build`를 실행합니다. 이후 시작은 `docker compose up -d`입니다.
4. 각 PC의 `http://PC주소:8088`로 접속합니다. Sender/Receiver 버튼은 해당 PC의 화면으로 이동합니다.

Java와 네이티브 실행기는 같은 컨테이너/JVM에서 동작합니다. BAT 또는 별도 Agent 실행은 필요 없습니다. 초기 DB 권한 준비 컨테이너는 작업 후 종료됩니다.

상대 PC 주소를 아직 정하지 않았다면 `LNIS_NODE_PEER_URL`을 비워 로컬 실행기와 화면만 먼저 시작할 수 있습니다. 송수신 시험 전에는 실제 상대 주소와 양쪽 공통 관리 토큰을 설정해야 합니다.

Windows의 WSL Docker에서 실행할 때는 `START.cmd`/`STOP.cmd`를 편의상 사용할 수 있습니다. 이 도우미는 Docker만 제어하며 Windows Agent를 실행하지 않습니다. USB 연결은 자동 구성하지 않습니다.

기존 중앙 서버용 `gradlew.bat deployComposeBundle`은 독립 노드로 전환된 운영 폴더를 덮어쓰지 않도록 중단됩니다. 독립 노드 배포는 `linuxNodeDistZip` 산출물을 사용하며 기존 `.env`와 `DB`는 유지하세요.

## 실제 GNSS 수집

송신 PC에 EVK-F9T를 연결하고 `.env` 끝의 `COMPOSE_FILE`, `LNIS_SERIAL_DEVICE`, `LNIS_SERIAL_GID`를 활성화합니다. 장치는 `/dev/serial/by-id/...`의 실제 경로를 지정하고 그룹 번호는 호스트의 장치 소유 그룹 번호를 사용합니다. 컨테이너 화면에서는 `/dev/ttyUSB0`을 선택합니다. 장치를 다시 꽂거나 경로가 변경되면 컨테이너를 재생성하세요.

USB 매핑 없이도 GRAW 파일 업로드 시험은 가능합니다. 기본 구성은 Linux용이며 Windows Docker/WSL의 USB 연결을 자동 구성하지 않습니다.

## 통신 및 저장

- 관리 REST 및 AFS 프레임 전송: 양쪽 TCP 8088(또는 `LNIS_SERVER_PORT`). 토큰은 URL에 넣지 않습니다. 신뢰하는 시험 LAN에서만 사용하고 외부망에서는 TLS/방화벽을 구성하세요.
- DTN: 송신 화면의 URL로 외부 어댑터에 POST합니다. 외부 수신 어댑터는 **수신 PC**의 `/lnis/api/v1/dtn/receive`에 같은 JSON과 `Authorization: Bearer 수신토큰`을 전달합니다.
- 송신 DB: 입력, 송신 JSON, 기준 PVT, 수신 PVT 및 비교 결과.
- 수신 DB: 사전 등록된 ID/해시, 최초 수신 JSON 원문, 복호화/PVT 결과. 기준 PVT나 송신 입력 파일은 복사하지 않습니다.
- 각 PC의 `DB` 폴더가 `/app/data`에 연결됩니다. 실행 중인 H2 파일을 복사하지 말고 `docker compose down` 후 백업하세요. 다른 노드의 DB를 덮어쓰거나 동일 DB를 두 프로세스에서 열면 안 됩니다.

관리 연결 실패 시 시험 데이터를 자동 재전송하지 않습니다. 재시작 후 진행 상태와 타임아웃을 확인하고 새 시험으로 다시 시작하세요. 외부 DTN 전달 실패로 수신 대기 항목이 남아도 10분 제한 후 실패로 정리됩니다.

`licenses/native-sources.zip`에는 Linux SO 재빌드 자료와 LDPC 원본 라이선스가 포함됩니다. 개발 빌드 절차는 저장소의 `native/build-linux.ps1`을 참고하세요.
