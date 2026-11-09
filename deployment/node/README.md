# 송신·수신 PC 설치와 실행

## Windows 11 오프라인 납품

지원 환경은 Windows 11 x64, WSL2의 Linux Docker Engine입니다. Docker Desktop, 별도 Java 설치, 원본 오픈소스 폴더는 필요하지 않습니다.

설치 담당자는 Windows 가상화 기능을 준비하고 실제 사용할 Windows 계정으로 Setup.exe를 실행합니다. Windows 기능 활성화와 방화벽 설정 때만 관리자 승인을 요청합니다. 재부팅 안내가 나오면 재부팅 후 같은 파일을 다시 실행합니다. Windows 구성요소의 원본이 없는 PC는 해당 Windows 설치 미디어가 필요합니다.

1. 각 PC에 `Setup.exe`를 복사해 실제 사용할 Windows 계정으로 실행합니다.
2. 송신 또는 수신 역할과 설치 위치를 선택합니다. 기본 위치는 `C:\compose`입니다.
3. 자동 선택된 현재 PC 주소를 확인합니다. 주소가 여러 개면 상대 PC와 연결된 네트워크의 주소를 선택합니다.
4. 상대 PC·어댑터 주소는 모르면 비워 둡니다. 설치 후 시험 화면에서 입력할 수 있습니다.
5. 기본 서비스 포트는 두 PC 모두 `8088`입니다. Docker·Windows 접속·서비스 포트는 자동으로 같은 번호를 적용합니다.
6. 서비스 연결 인증값은 양쪽이 같아야 합니다. 새 설치는 자동 생성되며, 상대 PC 설치 때 같은 값을 입력합니다. 어댑터 인증값은 기존 어댑터 설정과 맞춥니다.
7. 설치 중 재부팅을 안내하면 재부팅 후 같은 `Setup.exe`를 실행합니다. 입력값은 현재 Windows 사용자 기준으로 암호화해 보존하며 설치 성공 후 제거합니다.
8. 설치가 끝나면 바탕화면의 `GNSS 전송 시험 · 송신` 또는 `GNSS 전송 시험 · 수신`으로 실행합니다. `DTN STOP`은 서비스를 종료합니다.

설치 기록은 `%LOCALAPPDATA%\imtplus\Setup\install.log`에 남습니다. 설정 확인·Windows/WSL 준비·이미지 설치·실행·접속 포트·바로가기 생성 순으로 진행합니다. 실패 후 같은 EXE를 다시 실행할 수 있으며, 갱신 실패 시 기존 서비스·설정·DB 복구 절차를 유지합니다. 다른 WSL 배포판이나 시험 데이터를 자동으로 삭제하지 않습니다.

I/Q 실제 파일은 설치 위치의 `data\exchange`에 있습니다. 고급 설정의 I/Q 경로는 어댑터에서 사용하는 경로 표기이며, 바꿔도 실제 저장 위치는 이동하지 않습니다. 어댑터에서도 해당 폴더에 접근할 수 있도록 설정합니다.

ZIP 배포도 유지합니다. `lnis-node-windows.zip`을 모두 압축 해제한 뒤 `INSTALL.cmd`를 실행하는 기존 방식도 사용할 수 있습니다.

설정 파일을 미리 준비한 경우 INSTALL.cmd -Configuration "C:\준비\sender.env"처럼 전달할 수 있습니다. 모든 항목의 의미와 입력 예시는 app/.env.example에 한글로 적혀 있고 생성된 data/.env에도 남습니다. 토큰은 URL이나 전달 로그에 넣지 않습니다.

### 일반 사용자

- 바탕화면 GNSS 전송 시험 · 송신/수신: WSL 유지 프로세스 → Docker → Windows COM 중계 → 서비스 준비 확인 → 브라우저 열기.
- 바탕화면 DTN STOP: 서비스 → COM 중계 → 자신이 만든 WSL 유지 프로세스 종료.
- 브라우저나 CMD 창을 닫아도 서비스는 계속 동작합니다. PC 재부팅 후 START를 다시 실행합니다.
- WSL 터미널이나 숨김 PowerShell 명령을 따로 실행하지 않습니다. 반복 실행은 기존 프로세스를 재사용합니다.
- 실행 바로가기는 인터넷 다운로드·이미지 빌드를 하지 않습니다. 이미지를 찾지 못하면 INSTALL을 다시 실행합니다.

GNSS 장비가 없거나 COM 중계가 준비되지 않아도 파일 기반 시험은 사용할 수 있습니다. 웹 화면의 COM 포트 조회에서 장비와 통신 속도(기본 38400)를 선택합니다. u-center 등 같은 COM 포트를 쓰는 프로그램은 먼저 연결을 해제합니다. 수집 완료·브라우저 종료 후에도 연결은 유지되며 화면의 연결 해제로 반환합니다. 서비스 재기동 후에는 포트를 다시 연결합니다.

### 파일과 포트

- app: 버전이 고정된 Compose, START/STOP, COM 중계, 전용 Java, 배포 파일 목록.
- data: .env, DB, exchange, COM 설정·로그, WSL 유지 프로세스 기록.
- runtime: 전용 LNIS-Runtime WSL 배포판. 기본 Ubuntu나 다른 WSL 배포판을 변경하지 않습니다.
- backups: 갱신 전 서비스·설정과 정지 상태의 DB. 설치 갱신에 실패하면 이전 버전으로 복원합니다.

서비스 포트는 .env의 LNIS_SERVER_PORT 하나를 사용합니다. 설치 프로그램이 Windows LAN 주소의 같은 포트를 Windows loopback으로 전달합니다. WSL의 바뀌는 내부 IP를 규칙에 고정하지 않습니다. 주소·포트를 변경하면 INSTALL로 네트워크 설정도 갱신합니다. 다른 프로그램의 포트 전달 규칙은 덮어쓰지 않습니다. WSL NAT와 Windows localhost 전달이 정상인 환경이 기준이며, 운영체제의 전체 WSL 설정을 자동 변경하지 않습니다.

송수신 REST는 TCP 서비스 포트 하나를 사용합니다. 수신 어댑터는 수신 PC의 /lnis/api/v1/dtn/receive로 JSON과 수신 인증값을 전달합니다. 내부 시험 시각 보정은 OS 시각을 변경하지 않습니다. 외부 인터넷이 없는 경우 공통 내부 NTP 서버가 있으면 LNIS_NTP_SERVER에 입력하고, 없으면 비워 둡니다.

### 갱신과 장애 확인

새 Setup.exe에서 기존 설치 경로를 선택합니다. 시험 준비·송신·계산·수집 중에는 갱신하지 않습니다. 종료 후 DB를 백업하며, 기존 입력·I/Q·설정을 보존합니다. 실패하면 이전 앱·설정·DB를 복원합니다. 오류창에 중단 단계와 사유가 표시됩니다. 설치 기록은 `%LOCALAPPDATA%\imtplus\Setup`의 `install.log`에 남으며, 관리자 작업은 `install.log.admin.log`, WSL 설치는 `install.log.admin.log.wsl-msi.log`에서 확인합니다. 실행 중인 H2 파일을 복사하거나 다른 PC DB로 덮어쓰지 않습니다.

외부 어댑터 전달 대기는 시간 제한 없이 유지되며 testId가 같은 늦은 수신을 해당 시험에 반영합니다. 서비스 정상 실행과 상대 PC·어댑터 연결 상태는 별개입니다.

## 개발·배포 담당자

`gradlew.bat windowsNodeSetup`은 검증 후 프로젝트 최상위에 `Setup.exe`를 생성합니다. EXE는 ignore 항목에 추가하지 않습니다. `windowsNodeDistZip`은 기존 ZIP을 생성합니다. NSIS 3.13 제작 도구는 최초 빌드 시 공식 배포처에서 받아 고정 SHA-256으로 검증·캐시하며 설치 대상 PC에는 필요하지 않습니다. 첫 생성은 공식 WSL MSI와 Ubuntu/Docker 자료를 받기 위해 인터넷이 필요합니다. 준비 자료는 build/offline-assets에 해시와 함께 캐시되고, 납품한 묶음의 설치·실행에는 인터넷이 필요하지 않습니다. 릴리스 목록에는 MSI·Docker·Compose·서비스 이미지 버전과 파일 SHA-256이 기록됩니다.

기존 개발 설치의 갱신은 scripts/deploy-nodes.ps1을 사용합니다. check bootJar windowsSerialBridgeDist를 함께 빌드하고 -ValidateOnly로 먼저 확인할 수 있습니다. 이 경로는 기존 Compose 추가 설정과 .env·DB를 유지합니다.

## Linux 직접 실행

Linux x86-64 PC에서는 linuxNodeDistZip 묶음을 사용합니다. .env.example을 .env로 복사해 역할·주소·인증값을 설정한 후 최초 docker compose up -d --build, 이후 docker compose up -d로 실행합니다. Windows용 START/INSTALL은 사용하지 않습니다.

실제 USB를 직접 연결하려면 장치의 devices와 group_add를 Compose에 명시합니다. 장치 경로는 /dev/serial/by-id의 실제 값, 그룹 번호는 해당 장치의 소유 그룹을 사용합니다. 컨테이너·호스트 권한과 재연결을 확인해야 하며 privileged 또는 전체 쓰기 권한을 부여하지 않습니다. Windows COM 중계를 쓰는 장치를 동시에 WSL에 직접 연결하지 않습니다.

라이선스와 native-sources.zip은 배포에 포함됩니다. 외부 오픈소스 폴더는 실행에 필요하지 않으며, 네이티브 재빌드 자료는 별도로 보존합니다.
