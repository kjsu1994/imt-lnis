# 네이티브 원본과 LNIS 연결부

원본은 `vendor/`, 수정은 `patches/`, 서비스 연결 코드는 이 폴더에 둔다.
원본 파일을 함수 단위로 잘라내거나 주석/공백/줄바꿈을 수정하지 않는다.

## 출처와 담당 기능

| 원본 | 출처·기준 | 사용 기능 |
|---|---|---|
| LANS-AFS-SIM | 사용자 지정 `오픈소스/LANS-AFS-SIM-main`, 2026-09-22 원본 재반입 | AFS 부호화·I/Q 변조·PRN 코드 |
| PocketSDR-AFS | 사용자 지정 `오픈소스/PocketSDR-AFS-main`, 2026-09-22 원본 재반입 | AFS LDPC 복호화·내장 RTKLIB 지구 PVT |
| LDPC-codes | https://github.com/radfordneal/LDPC-codes · `74a8e283be8259dbff7a6bab38ad7e9327825cbf` | PocketSDR 복호기의 희소행렬·확률 복호화 |

LANS/PocketSDR는 `O:\3.ing\LNIS\오픈소스`의 사용자 지정 기준 원본이며 공식 저장소의 특정 커밋과 동일하다고 보증하지 않는다. 50개 파일을 해당 사본과 바이트 단위로 맞췄다. LDPC-codes 32개는 기존 고정 버전을 유지한다.
관련 프로젝트: https://github.com/osqzss/LANS-AFS-SIM / https://github.com/osqzss/PocketSDR-AFS
LDPC는 원래 PocketSDR의 `lib/clone_lib.sh`에서 별도 확보하던 의존성이다. 별도 서비스로 실행하지 않는다.
LDPC-codes는 Git 원본 바이트로, 제공본은 제공된 파일 바이트로 보존했다. 파일별 기준은 `UPSTREAM-SHA256.txt`다.
LANS에 포함된 mod2sparse와 LDPC-codes의 버전은 다르므로 임의로 합치지 않는다.

## 변경 패치

- `01-korean-comments`: 기존 한글 설명 주석 71개를 실행 코드 변경 없이 보존한다. 제거된 진단 로그의 설명은 파일 앞부분에 이력 주석으로 모으고, 계산 설명은 해당 코드 곁에 둔다. 이전 `01-logging` 우회 패치는 제거했다.
- `02-earth-iq`: 기존 LNIS의 지구 입력·GPS PRN 1~32·궤도/거리 연결, 심볼 이름 충돌 방지, PRN별 증거 저장, 90초 길이 보정을 재현한다. 변조·FEC·각 PRN 프레임 반복은 원본 경로다.
- `03-decoder-includes`: 디코더와 관계없는 FFTW/USB/CyAPI 헤더 대신 필요한 선언만 포함한다. Windows DLL도 미리 빌드된 `.a` 없이 동일 소스로 만든다.

각 수정 위치의 `LNIS 변경` 주석에서 목적과 내용을 확인한다. 빌드 사본의 C/H 줄바꿈만 LF로 정규화하며 vendor에는 쓰지 않는다.
빌드 후 `build/native-output/modified-sources/`에서 주석이 포함된 실제 수정본을 직접 읽을 수 있다.
이 파일들은 빌드 때 재생성되므로 직접 고치지 말고 대응 패치를 수정한다.

### 진단 로그 정리

비트·16진수·CRC 재계산·LDPC 패리티 비교를 위해 추가했던 진단 덤프는 완전히 제거했다.
로그 전용 메모리 할당·배열 복사·잠금·카운터와 `afs_sim_log.txt` 생성도 제거한다.
원본의 부호화/복호화 알고리즘, 계산에 필요한 잠금, 오류·경고·진행률 출력은 유지한다.
PRN별 `.afsbits` 검증 파일도 그대로 생성한다.

`$IQOBS`와 `$IQAFS`는 Java PVT 계산기가 사용하는 데이터 전달 형식이다. 이름이나 저장 위치가 로그처럼 보여도 삭제하거나 출력 수준을 낮춰 차단하면 안 된다.
서비스 화면과 Docker의 시험 상세 로그도 유지한다. 현재 원본 출력은 생성·수신 작업별 파일에 보관하며 기존 Java 오류 전달 경로를 사용한다.

송신 비트 출력은 기존에도 우회됐고, 수신 비트 덤프는 PRN 8의 정상 프레임 최초 2개에만 실행됐다.
따라서 로그 정리만으로 큰 처리속도 향상을 보장하지 않는다. 생성·복조, 파일 복사, SHA-256 검증 시간을 나눠 측정한다.
I/Q 생성 난수는 현재 시각을 seed로 사용하므로 별도 생성 파일의 SHA가 같아야 한다고 검사하지 않는다.
수신 반복 시험에서는 복호된 프레임 비트는 정확히 비교하되, 비동기 탐색·추적의 초기 조건에 따른 관측값/PVT 변동은 기존 바이너리의 반복 결과와 함께 기록한다. 상대 누적 반송파 위상은 추적 시작 기준이 달라질 수 있으므로 절대값 일치를 요구하지 않는다.

빠른 Linux 파일시스템에서 확인된 고속 파일 재생의 조기 종료 문제는 `06-file-replay.patch`와 `iq_file_replay.h`로 보완한다. 파일 입력은 가장 느린 채널보다 약 100 ms 이상 앞서 읽지 않으며, EOF에서도 모든 채널의 처리 가능한 상관 구간이 끝날 때까지 기다린다. 상관 계산에 필요한 다음 샘플이 없는 마지막 불완전 구간은 원본처럼 계산하지 않는다.

채널별 완료 위치와 취소 요청은 원자적으로 공유한다. 대기 중 특정 채널이 30초 동안 진행하지 않거나 파일 읽기 오류·불완전 입력 블록이 발견되면 비정상 종료하며, Java의 기존 실행 실패 경로가 부분 결과의 정상 접수를 막는다. 시험 취소는 EOF 대기를 중단한다. `-tscale 20`은 유지하되 실제 처리 속도가 따라오지 못하면 읽기를 늦춘다. USB 입력에는 파일 대기와 파일 오류 정책을 적용하지 않는다.

`20배속`은 저장된 I/Q 파일 재생 옵션이며 신호의 샘플 시각·샘플링 주파수·Doppler를 변경하지 않는다. I/Q 생성 속도나 DTN 전송 속도 설정도 아니며, 실제 처리 시간이 정확히 1/20로 줄어드는 것을 보장하지 않는다.

수신기 빌드에서 `test_iq_replay.cpp`로 속도가 다른 소비자, 버퍼 순환, EOF 잔여 처리, 정지 시간 초과, 취소를 검증한다. `verification/file-replay.txt`에 결과를 내보낸다. 실제 RF 검증은 빠른 디스크의 동일 파일을 1배속/20배속으로 재생하고, 종료 메시지 `I/Q replay complete: cycles=... channels=... pending=0`와 복원 프레임·관측값·PVT를 함께 확인한다. 신호 미획득이나 CRC 실패로 인한 관측 부족까지 없애는 기능은 아니다.


회귀 검사는 `gradlew.bat check -PnativeCandidate=build/native-pvt`로 새 DLL을 지정한다. `nativeSourceTest`는 원본 해시와 제거 대상 심볼, 필수 IQ 데이터 출력을 확인한다.
실제 RF 비교 자료가 있으면 `LNIS_NATIVE_LOG_COMPARISON`에 `source.json`, `source-no8.json`, `tracking-{prn8|no8}-{baseline|candidate}-{1|2|3}.log`가 있는 폴더를 지정한다. `NativeDiagnosticRemovalTest`가 프레임 일치·관측 개수·PVT 반복 차이를 검사하고 `build/reports/native-clean/pvt-repetition.json`에 수치를 기록한다. 자료가 없으면 이 선택적 테스트는 생략한다.

현재 입력 어댑터는 GPS L1 C/A를 지원한다. 다중 GNSS 전체 지원은 포함하지 않는다.

### AFS + Metadata 전송 (v2/v3)

서비스 연결부 `src/main/java/server/agent/dtn/AfsMetadataCodec.java`에서 GPS LNAV를 원본 `afs_sim.c:eph2sbf`와 같은 SB2 배치로 옮긴다. toe/toc, e, sqrtA, i0, Ω0, ω, M0, af0/af1을 담고 SB3/SB4는 원본의 교대 0101 데이터 비트를 유지한다. CRC/FEC는 기존 네이티브 인코더/디코더를 그대로 호출한다. vendor·패치·ABI는 이 변경으로 수정하지 않는다.

LNIS 목적의 차이는 **달 궤도값 대신 GPS 항법값 사용**, **RAWX와 SB2에 없는 GPS LNAV 필드를 JSON으로 보조**하는 것이다. JSON 항법 words에서는 SB2로 전달한 비트를 0으로 비워 중복하지 않는다. GPS 이심률 단위 2^-33과 원본 AFS 단위 2^-32의 차이 때문에 마지막 1 bit는 JSON에 남긴다. 수신은 SB2를 원위치에 채우고 원본 GRAW SHA-256을 검증한다. 따라서 수신 RAW 표와 지구 PVT는 원래 관측값·항법정보를 사용하며 SB2 양자화로 계산 정밀도를 잃지 않는다. 해당 Java 코드에 배치와 목적을 주석으로 기록했다.

기존 AFS Frame 오류 주입 시험의 `AfsFrameBuilder`/GRAW fragment 경로와 과거 DTN AFS v1 수신은 호환을 위해 유지한다. I/Q는 아래의 별도 추적 경로를 사용한다.

v3는 이 AFS 비트 생성/복원 방식을 바꾸지 않고 JSON만 `satellites[]`의 PRN별 프레임·관측값·보조 항법정보로 묶는다. `recordIndex`/`measurementIndex`로 원본 레코드와 신호 순서를 복원한 뒤 v2 검증 경로를 재사용한다. 관측 시점이나 항법 갱신이 여러 개라도 첫 항목만 남기지 않는다. 수집 정보와 RAWX 공통 헤더는 최상위 `metadata.commonRecords`에 한 번만 보존한다.

### I/Q 지구 수신 경로

실제 90초 파일 시험에서 발견한 원본 로그 버퍼의 CR/LF/NUL 길이 누락과 RTKLIB 스트림 옵션 배열(5→8항목)도 `04-iq-receiver.patch`로 보정한다. 원본은 그대로 보관하며 수정한 수신 경로는 AddressSanitizer로 전체 파일을 재검증한다.

`04-iq-receiver.patch`는 원본 추적기의 탐색 도플러 범위·신호 임계값·GPS PRN 범위를 조정하고, CRC 통과 채널의 샘플 시각/코드 지연/도플러/누적 위상/C/N0를 출력한다. 원본 달 PVT 경로 대신 `IqReceiver` → 공유 `NativePvtCodec` → 기존 `lnis_pvt_gps_solve`로 연결한다. 실시간 채널 집계 경합을 피하려고 파일 처리 후 같은 샘플 시각끼리 합친다. 2 ms 상관 구간의 종료 TOW를 샘플 시작 시각으로 맞춰 의사거리를 구한다.

원본 AFS SB2만으로 GPS LNAV의 모든 보정항을 보존하지 못하므로 선택 PRN의 LNAV를 JSON 메타데이터로 보조한다. 기준 PVT는 이 계산 경로에 넣지 않는다. 생성기 거리 감쇠 기준은 달의 5,200 km에서 GPS의 20,200 km로 변경하며 변조·LDPC·잡음 생성 방식은 유지한다. 현재 생성기는 대기 지연을 합성하지 않지만 기존 SPP 보정은 유지하므로 잡음·모델 차이에 따른 미터급 오차가 남는다. 수신 PVT를 기준값에 강제로 맞추지 않는다.

추가 vendor 파일은 PocketSDR의 파일 입력·추적·복조와 링크에 필요한 RTKLIB 함수의 **주석 포함 원본**이다. SHA-256 목록으로 전부 검사한다. 기존 RAW/AFS용 코덱과 Windows DLL의 알고리즘·ABI는 바꾸지 않는다. Linux 수신 실행기는 FFTW/libusb/libfec 런타임을 사용하며 배포 이미지가 함께 설치한다.

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


## AFS v4 / I/Q frame payload extension

`patches/05-afs-pvt-payload.patch` supplies the Java common encoder's SB2/SB3/SB4 input bits to the existing modulator. `iq_earth.c` accepts `LNIS-IQ-EARTH-2` with `F <PRN> <1176 bits> <846 bits> <846 bits>` records; the legacy input remains readable. Original CRC/FEC/interleaving/modulation implementations are retained.

PocketSDR emits `$IQAFS,<sample time>,<PRN>,<block>,<packed hex>` only after LDPC/CRC success. SB2 exports 147 bytes; SB3/SB4 export 106 bytes with the last two padding bits zero. The Java receiver combines blocks from the same decoded frame, validates the LNIS extension, and supplies recovered navigation to RTKLIB. The tracked pseudorange/Doppler remain the RF solver's observations. The local type 63/version 2 payload is not an official message assignment. See README for bit offsets and the 6000-bit layout.

실제 파일 재생 PVT 회귀 검사는 `LNIS_IQ_REPLAY_DIRECTORY`에 90초 검증 자료(`source.json`, `tracking-0-20.log`, `tracking-1-1.log`, `tracking-2-20.log`)가 있는 폴더를 지정하면 실행한다. `NativeFileReplayIntegrationTest`는 JSON 항법 보조 없이 마지막 Epoch까지 위치·속도가 유효한지 검사한다. 자료가 없으면 생략한다.
