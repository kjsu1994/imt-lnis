# 실제 GNSS 수집

`real-gnss-10epochs.graw`는 연결된 수신기의 실제 UBX 아카이브에서 추출한 서로 다른 10개 관측 에폭과 그 이전/사이의 항법정보다. GNSS 기준시간 선택 상자에서 한 에폭을 고르면 해당 에폭이 전송 요청에도 반영된다. 유효한 PVT가 없는 에폭은 기존 지연 반영 시험 조건을 충족하지 못하므로 자동으로 다른 에폭으로 바꾸지 않고 실행을 막는다.

숨겨진 개발용 버튼은 `lnis.dtn.real-file`로 지정한 이 실측 파일을 불러온다. `lnis.dtn.example-enabled=true`일 때만 서버 재생 API가 활성화되며, 합성 파일로 대체하지 않는다. 이것은 저장 데이터 재생이고 COM 버튼은 별도의 새 1에폭 수집이다.

송신 화면에서 COM 포트 입력을 선택하고 `포트 조회`로 장치를 확인한 뒤 속도를 지정하고 `1에폭 수집`을 누른다. 포트 목록은 송신 서비스의 운영체제에서 다시 조회하며 장치 설명과 USB VID/PID를 함께 보여 준다. 조회만으로 포트를 열지 않으므로 사용 중 여부는 수집 시작 때 확인한다.

수집은 최대 120초 동안 항법정보를 모으며, 기존 지구 PVT 엔진에서 위치와 속도가 모두 유효한 **관측 에폭 하나**를 확보하면 자동으로 종료한다. 파일에는 그 에폭과 계산에 필요한 이전 SFRBX 메시지, 수신기 메타데이터가 들어간다. 첫 바이트나 첫 NMEA 문장 하나를 1에폭으로 취급하지 않는다. 관측 0개, 항법정보 부족, 시간 정체, 포트 무응답을 화면 상태와 수신 카운터로 구분한다. 무관측 상태를 성공 파일로 만들지 않는다.

## USB와 RS-232

- 두 경로 모두 UBX-RXM-RAWX와 UBX-RXM-SFRBX를 사용한다. NMEA 위치 문장만으로 원시 의사거리·반송파 위상을 복원할 수 없다.
- u-blox 현재 포트 설정과 원래 메시지 출력률을 먼저 조회한다. RAWX/SFRBX를 현재 포트에만 임시 활성화하고 재조회로 확인한다. 종료/실패 시 원래 출력률을 복원한다. BBR/Flash 영구 설정이나 다른 포트 출력률은 쓰지 않는다.
- RS-232 속도는 실제 수신기 UART 속도와 맞춘다. 장치관리자 기본 속도가 응용프로그램의 포트 설정을 대신하지 않는다. 수집기는 8N1, 흐름제어 없음으로 연다.
- USB에서 비어 있더라도 지원되지 않는 데이터라고 단정하지 않는다. RAWX의 모든 위성·신호와 SFRBX 워드는 GRAW에 보존한다. 현재 PVT 엔진의 계산 대상은 GPS L1 C/A다.
- RAWX의 실제 버전(0/1)을 유지하고, 알 수 없는 버전과 길이 불일치를 거부한다. 기존 LNIS GRAW 스키마는 유지된다. `.ubx` 파일을 이름만 바꾼 것이 GRAW는 아니다.

## 실행 위치

Windows에서 서비스를 실행하면 COM 포트가 표시된다. WSL/Docker에서는 Windows COM이 자동 노출되지 않는다. usbipd-win으로 대상 USB를 WSL에 전달하고 `docker-compose.serial.yml`의 `LNIS_SERIAL_DEVICE`와 그룹을 설정해야 한다. 이때 `/dev/ttyACM0` 또는 `/dev/ttyUSB0` 등 Linux 이름으로 선택한다. 전달 중에는 Windows 유센터가 같은 USB를 사용할 수 없다.

## 명령행 진단과 GRAW 생성

Java 21을 사용한다.

```powershell
.\gradlew.bat captureGraw --args="--ports"
.\gradlew.bat captureGraw --args="COM5 38400 data/real-epoch.graw"
```

실제 포트 번호를 조회 결과에 맞춘다. 성공하면 `real-epoch.graw`에 관측 에폭이 정확히 하나 저장된다. `real-epoch.graw.ubx`에는 설정 조회 중 수신한 바이트를 포함한 원본 스트림을 별도로 남긴다. 타임아웃·실패 시 GRAW는 확정하지 않고 UBX 진단 파일만 보관한다. 기존 파일을 덮어쓰지 않는다. 강제 프로세스 종료나 전원 분리 때는 설정 복원이 불가능할 수 있다.

RAW 화면은 실측값과 유효성 플래그를 보존한다. 윤초 유효 비트가 꺼져 있으면 숫자를 확정된 UTC 보정값으로 표시하지 않는다. 의사거리로 구한 위성 송신 시각은 별도 수신 필드가 아니라 보정 전 추정값이다.

참조: [u-blox F9 TIM 2.25 Interface Description](https://content.u-blox.com/sites/default/files/documents/u-blox-F9-TIM-2.25_InterfaceDescription_UBXDOC-963802114-13231.pdf), [Microsoft WSL USB 연결](https://learn.microsoft.com/en-us/windows/wsl/connect-usb).

## 이번 실측 파일의 검증 결과

- 파일: `real-gnss-10epochs.graw` (25,924 bytes), SHA-256 `20dd75840e65bf6761d1b37c4614dc3903e2d8f1a43c5bd695d317760375f586`.
- 10개 서로 다른 에폭: GPS Week 2438, TOW 197060.004 ~ 197069.004 s. 선행·중간 항법 메시지 189건.
- `real-gnss-source.ubx`에서 모든 관측값·유효성 플래그·항법 워드가 실제 수신 바이트와 일치함을 자동 시험으로 확인했다. GRAW 파일을 새로 읽어 CRC/구조도 검증했다.
- 실제 웹서비스에서 USB 전달 포트 `ttyACM0`와 u-blox VID 1546 / PID 01A9를 조회하고, 숨겨진 실측 재생 버튼으로 10에폭을 불러와 각 에폭의 표 행수·시각을 확인했다.
- 이 구간의 PVT는 무효다. 현재 GPS L1 계산기에 필요한 위성별 항법정보가 부족하므로 유효한 기준 위치·속도가 필요한 지연 시험은 진행할 수 없다. 합성 항법정보를 보충하지 않았다.
- `capturedAt`은 원본 UBX 파일의 저장 시각이다. 원시 UBX에는 개별 메시지의 PC 도착 시각이 없으므로 이를 정밀 수집 타임스탬프로 해석하지 않는다. 에폭별 GNSS 시간은 RAWX Week/TOW를 사용한다.