# WSL2 Docker Engine 운영

Windows의 WSL2 배포판 안에 직접 설치된 Linux Docker Engine에서 실행한다.
집의 Docker Desktop은 파일 기반 개발용으로 유지한다. 별도 Windows 수집 서비스는 없다.
WSL 터미널에서 실행해도 Docker Desktop 엔진에 연결될 수 있으므로
`docker context show`, `docker info`로 실제 서버를 확인한다.

## 송신 PC 준비

1. WSL2, Linux Docker Engine/Compose, usbipd-win을 설치한다.
2. Windows PowerShell에서 `usbipd list`로 F9T USB 장치의 BUSID를 확인한다.
3. 관리자 PowerShell에서 `usbipd bind --busid <확인한-BUSID>`를 실행한다.
4. WSL 배포판을 실행해 둔 상태에서 `usbipd attach --wsl --busid <확인한-BUSID>`.
5. WSL에서 `lsusb`와 `ls -l /dev/ttyACM* /dev/ttyUSB*`로 직렬 장치를 확인한다.
   USB 목록만 보이고 직렬 장치가 없다면 WSL 커널/드라이버부터 확인한다.
6. 실제 장치에 대해 `stat -c '%g %a' /dev/ttyACM0`으로 그룹 ID와 권한을 확인한다.
   그룹 읽기·쓰기 권한이 필요하다. 전역 chmod 777이나 privileged 컨테이너는 사용하지 않는다.

기존 `docker-compose.serial.yml`을 재사용하고 송신 `.env`의 직렬 장치 설정을 활성화한다.
장치 경로와 그룹 ID는 위 확인 결과로 교체한다(20은 예시).

```dotenv
COMPOSE_FILE=docker-compose.yml:docker-compose.serial.yml
LNIS_SERIAL_DEVICE=/dev/ttyACM0
LNIS_SERIAL_GID=20
```

이후 WSL 터미널의 배포 폴더에서 평소처럼 실행한다.

```sh
docker compose up -d
docker compose exec node ls -l /dev/ttyUSB0
```

화면에서 COM 포트 → 포트 조회 → ttyUSB0 선택 → 1에폭 수집. 장치 설명과 VID/PID도 확인한다.
유효한 GPS L1 지구 위치·속도를 얻은 첫 시점에서 자동 종료한다(최대 120초).
보드레이트는 UART 연결 시 수신기 설정에 맞춘다. USB 직렬 연결의 동작은 실장비로 확인한다.
RAWX/SFRBX가 나오고 안테나가 충분한 위성을 수신해야 한다.

WSL에 연결된 동안 Windows 프로그램은 해당 USB를 동시에 사용할 수 없다.
재부팅/USB 재연결 시 attach와 장치 경로·권한을 다시 확인한다.
장치가 재생성되면 수집이 없는 상태에서 `docker compose up -d --force-recreate node`로
매핑을 갱신한다. 장치 미연결 상태에서는 GNSS override 기동이 실패할 수 있다.

수신 PC와 Docker Desktop 파일 테스트에는 GNSS override를 사용하지 않는다.
USB 연결은 Compose 자체가 수행하지 않는다. WSL의 LAN 접속/방화벽 설정도 별도 검증한다.

공식 참고:
- https://learn.microsoft.com/en-us/windows/wsl/connect-usb
- https://docs.docker.com/reference/compose-file/services/#devices
- https://docs.docker.com/reference/compose-file/services/#group_add

검증 범위: 자동화된 입력 선택/네이티브 계산/화면 테스트와 실측 장치 검증은 별개다.
2026-09-29 실장비 검증: ZED-F9T-20B USB를 WSL에 전달하여 웹의 실제 장치 조회와 RAWX/SFRBX 수신을 확인했다. 저장된 실측 10에폭의 불러오기·개별 선택도 확인했다. 위성 항법정보 부족으로 해당 실측 자료의 유효 PVT는 확보하지 못했다.

## 유센터에 USB 반환

WSL이 USB를 사용하는 동안 Windows COM5가 사라지는 것은 정상이다. 웹 수집이 끝나고 노드가 READY인지 확인한 뒤 직렬 장치 override를 제외하여 웹서비스를 재기동한다. Windows PowerShell에서 다음을 실행한다. BUSID는 `usbipd list`에서 다시 확인한다.

```powershell
usbipd detach --busid 2-1
# 공유 등록도 해제하려면 관리자 PowerShell에서 실행한다.
usbipd unbind --busid 2-1
```

Windows 장치 목록에 COM5가 돌아오면 유센터에서 해당 포트를 열 수 있다. 웹은 파일 기반 GRAW 재생을 계속 사용할 수 있다. 동일 USB의 Windows/WSL 동시 직접 사용은 지원하지 않으며, COM 중계 기능은 이번 변경에 포함하지 않는다.