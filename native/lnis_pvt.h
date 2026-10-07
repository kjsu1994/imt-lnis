#ifndef LNIS_PVT_H
#define LNIS_PVT_H
#include "lnis_afs_codec.h"

#define LNIS_SYS_NONE 0x00
#define LNIS_SYS_GPS  0x01
#define LNIS_SYS_GAL  0x08
#define LNIS_SYS_BDS  0x20
#define LNIS_SYS_ALL  0x29

/* RTKLIB의 내부 구조체를 Java에 노출하지 않는 Multi-GNSS PVT 확장 ABI다. */
LNIS_API uint32_t lnis_pvt_get_abi_version(void);
LNIS_API void *lnis_pvt_create(void);
LNIS_API void lnis_pvt_destroy(void *context);

/* 범용 항법 메시지 입력:
 * sys: LNIS_SYS_GPS (GPS L1 C/A 24-bit word 10개)
 *      LNIS_SYS_BDS (BeiDou B1I D1 30-bit word 10개)
 *      LNIS_SYS_GAL (Galileo E1-B I/NAV 32-bit word 8개)
 * 반환 1=새 궤도력 등록 성공, 0=서브프레임 보관(완성 대기), -1=입력 오류. */
LNIS_API int32_t lnis_pvt_navigation(void *context, int32_t sys, int32_t prn,
    int32_t observation_week, const uint32_t *words, uint32_t count);

/* 범용 PVT 계산:
 * navsys: 연산에 투입할 위성군 마스크 (LNIS_SYS_GPS, LNIS_SYS_BDS, LNIS_SYS_GAL, LNIS_SYS_ALL)
 * observations: 위성별 [sys, prn, 의사거리(m), doppler(Hz), cno(dB-Hz)] 5개 double 튜플.
 * count: 관측값 개수 (최대 64개)
 * result: ECEF 위치 3개, ECEF 속도 3개, 수신기 시계 오차(s), 사용 위성 수, 속도 유효 여부 (총 9개 double).
 * 반환 1=위치 해 성공, 0=측위 불가, -1=입력 오류. */
LNIS_API int32_t lnis_pvt_solve(void *context, int32_t navsys, int32_t week, double tow,
    const double *observations, uint32_t count, double *result,
    char *message, uint32_t message_size);

/* GPS L1 C/A 전용 하위 호환 함수 (ABI 호환성 유지) */
LNIS_API int32_t lnis_pvt_gps_navigation(void *context, int32_t prn,
    int32_t observation_week, const uint32_t *words, uint32_t count);
LNIS_API int32_t lnis_pvt_gps_solve(void *context, int32_t week, double tow,
    const double *observations, uint32_t count, double *result,
    char *message, uint32_t message_size);
#endif
