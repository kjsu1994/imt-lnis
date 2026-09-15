# 네이티브 원본과 LNIS 연결부

원본은 `vendor/`, 수정은 `patches/`, 서비스 연결 코드는 이 폴더에 둔다.
원본 파일을 함수 단위로 잘라내거나 주석/공백/줄바꿈을 수정하지 않는다.

## 출처와 담당 기능

| 원본 | 출처·기준 | 사용 기능 |
|---|---|---|
| LANS-AFS-SIM | 사용자 제공 `LANS-AFS-SIM-main` 로컬 사본, 2026-09-15 반입 | AFS 부호화·I/Q 변조·PRN 코드 |
| PocketSDR-AFS | 사용자 제공 `PocketSDR-AFS-main` 로컬 사본, 2026-09-15 반입 | AFS LDPC 복호화·내장 RTKLIB 지구 PVT |
| LDPC-codes | https://github.com/radfordneal/LDPC-codes · `74a8e283be8259dbff7a6bab38ad7e9327825cbf` | PocketSDR 복호기의 희소행렬·확률 복호화 |

LANS/PocketSDR는 로컬 변경이 포함된 제공본이며 공식 저장소와 동일하다고 보증하지 않는다.
관련 프로젝트: https://github.com/osqzss/LANS-AFS-SIM / https://github.com/osqzss/PocketSDR-AFS
LDPC는 원래 PocketSDR의 `lib/clone_lib.sh`에서 별도 확보하던 의존성이다. 별도 서비스로 실행하지 않는다.
LDPC-codes는 Git 원본 바이트로, 제공본은 제공된 파일 바이트로 보존했다. 파일별 기준은 `UPSTREAM-SHA256.txt`다.
LANS에 포함된 mod2sparse와 LDPC-codes의 버전은 다르므로 임의로 합치지 않는다.

## 변경 패치

- `01-logging`: 제공본의 손상된 문자열이 있는 로그 함수 두 개를 기존 서비스처럼 우회한다. AFS 계산은 변경하지 않는다.
- `02-earth-iq`: 기존 LNIS의 지구 입력·GPS PRN 1~32·궤도/거리 연결, 심볼 이름 충돌 방지, PRN별 증거 저장, 90초 길이 보정을 재현한다. 변조·FEC·각 PRN 프레임 반복은 원본 경로다.
- `03-decoder-includes`: 디코더와 관계없는 FFTW/USB/CyAPI 헤더 대신 필요한 선언만 포함한다. Windows DLL도 미리 빌드된 `.a` 없이 동일 소스로 만든다.

각 수정 위치의 `LNIS 변경` 주석에서 목적과 내용을 확인한다. 빌드 사본의 C/H 줄바꿈만 LF로 정규화하며 vendor에는 쓰지 않는다.
빌드 후 `build/native-output/modified-sources/`에서 주석이 포함된 실제 수정본 3개를 직접 읽을 수 있다.
이 파일들은 빌드 때 재생성되므로 직접 고치지 말고 대응 패치를 수정한다.

현재 입력 어댑터는 GPS L1 C/A를 지원한다. 다중 GNSS 전체 지원은 포함하지 않는다.

## 원본 유지 원칙

- 기존 lnis_afs_* ABI와 AFS 부호화/복호화 동작은 유지한다.
- 같은 LnisAfsCodec.dll에 lnis_pvt_* 인터페이스를 추가한다.
- PocketSDR-AFS의 src/sdr_pvt.c 일반 GNSS 경로와 내부 RTKLIB의 pntpos()를 기준으로 한다.
- 달 환경용 sdr_pvt_afs.c는 이번 지구 GNSS 전달 시험에 사용하지 않는다.
- 원본 파일명, 함수명, 포맷을 유지하고 장치 입력 변환과 ABI 래퍼는 별도 파일에 둔다.
- 원본 변경이 필요한 경우 원본 해시, 파일, 변경 이유와 diff를 기록한다.

## 직접 작성할 부분

PocketSDR의 RTKLIB rcvraw.c에는 2024/04/03 이력으로 수신기별 함수를 제거했다고 명시되어 있다.
헤더에 input_ubx() 선언이 있어도 구현이 포함되어 있다고 가정해서는 안 된다.
RAWX 관측값과 SFRBX 항법 메시지를 obsd_t/nav_t와 원본 항법 해석 함수에 연결하는 어댑터가 필요하다.
위성군별 메시지 해석을 확인한 뒤 지원 범위를 명시한다.

## 계산 재현성

- 양쪽은 동일한 관측 시각, 항법 갱신 순서와 계산 설정을 사용한다.
- DTN 도착 시각이나 현재 PC 시각으로 관측 시각을 대체하지 않는다.
- 외부 항법 캐시를 암묵적으로 읽지 않고 초기 상태를 재현한다.
- 측위 불가와 좌표 0, 속도 계산 불가와 유효 속도 0을 구분한다.
- 송신 기준 PVT는 비교용 JSON 필드로 전달한다. Receiver는 해당 값을 계산 입력으로 쓰지 않고 복원 데이터로 독립 계산한다.
- PVT 일치는 전달 전후 계산의 일치성이며 절대 위치 정확도를 증명하지 않는다.

## 빌드와 검증

저장소 루트에서 `gradlew.bat nativeBuild` 또는 `./gradlew nativeBuild`를 실행한다.
Linux SO/IQ는 `build/native-linux`, `build/iq`, Windows 후보는 `build/native-pvt`에 생성한다.
기존 `native/bin/win-x64` DLL과 실행 중인 서비스는 자동 교체하지 않는다.
전체 JDK 21이 필요하다. Agent용 축소 런타임으로 테스트하면 Mockito attach 기능이 없어 실패할 수 있다.

소스 ZIP만으로 재빌드할 때는 압축을 푼 폴더에서 다음을 실행한다.

```sh
docker build --platform=linux/amd64 --output type=local,dest=./output .
```

Docker가 고정된 기본 이미지에서 도구를 준비하고 네트워크가 차단된 단계에서 소스를 컴파일한다.
I/Q는 기존 GCC 14 이미지, SO는 운영 환경에 맞는 Ubuntu 24.04, Windows DLL은 MinGW를 사용한다.
기본 이미지 digest와 주요 컴파일러 패키지 버전을 고정했다. 저장소에서 해당 패키지가 사라지면
임의 최신 버전으로 바꾸지 말고 버전 갱신과 회귀 검증을 함께 수행한다.
`output/verification`에는 Linux AFS/PVT 시험 결과와 도구 버전이 남는다.
Windows에서는 `output/native-pvt`에서 `test_pvt.exe`, `test_afs.exe`를 실행한다.
모든 소스 ZIP에는 RTKLIB·LANS·PocketSDR·LDPC의 라이선스와 변경 패치가 포함된다.

lnis_pvt.c는 직접 작성한 입력/ABI 어댑터이며 pntpos와 decode_frame을 호출한다.
관측 주차로 GPS week rollover를 보정하고 위치·속도 유효성을 구분한다.
GPS 전용이므로 sdr_pvt.c의 비GPS 시간계 fallback은 사용하지 않는다.
포함한 RTKLIB의 라이선스 전문은 RTKLIB-README.txt에 보관한다.

native/test_pvt.c는 원본 계산 함수로 합성 관측값을 생성하고 알려진 위치와 독립 컨텍스트 결과를 비교한다.
실제 GNSS 장비나 외부 DTN 프로토콜 시험을 대신하지 않는다.
Java NativePvtIntegrationTest는 기존/확장 DLL의 AFS 출력과 수신 복원 결과를 검증한다.

UBX-RXM-SFRBX v2는 신호 식별자 위치가 reserved0인 펌웨어가 있으므로,
Java 입력 어댑터에서 LNAV preamble 위치까지 확인하여 CNAV가 계산에 섞이지 않게 한다.
기존 GRAW 저장 형식과 원본 RTKLIB는 변경하지 않는다.
메시지 배열 참고: https://content.u-blox.com/sites/default/files/ZED-F9T-10B_IntegrationManual_UBX-20033630.pdf (3.12.1.2)
검토한 원본 파일의 고정 해시 목록은 UPSTREAM-SHA256.txt에 보관한다.
