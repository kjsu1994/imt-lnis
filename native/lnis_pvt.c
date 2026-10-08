#include "lnis_pvt.h"
#include "rtklib.h"

/* 직접 작성한 연결 코드다. 항법 해석과 위치/속도 알고리즘은 원본 RTKLIB를 호출한다.
 * 컨텍스트는 시험마다 새로 생성하며 다른 시험의 항법 캐시를 공유하지 않는다. */
typedef struct {
    nav_t nav;
    uint8_t gps_frames[32][150];
    uint8_t bds_frames[64][190];
    uint8_t gal_frames[36][128];
} lnis_pvt_context;

uint32_t lnis_pvt_get_abi_version(void) { return 2; }

void *lnis_pvt_create(void) {
    lnis_pvt_context *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) return NULL;
    ctx->nav.eph = calloc(MAXSAT, sizeof(eph_t));
    if (!ctx->nav.eph) { free(ctx); return NULL; }
    ctx->nav.n = ctx->nav.nmax = MAXSAT;
    return ctx;
}

void lnis_pvt_destroy(void *context) {
    lnis_pvt_context *ctx = context;
    if (!ctx) return;
    free(ctx->nav.eph);
    free(ctx);
}

/* Galileo UBX-RXM-SFRBX 8 word (Even 128 bit + Odd 128 bit) CRC24Q 검증 */
static int unpack_gal_inav(const uint32_t *dwrd, uint8_t *buff) {
    uint8_t crc_buff[26] = {0};
    int i, j;

    for (i = 0; i < 8; i++) {
        buff[i*4]   = (uint8_t)(dwrd[i] >> 24);
        buff[i*4+1] = (uint8_t)(dwrd[i] >> 16);
        buff[i*4+2] = (uint8_t)(dwrd[i] >> 8);
        buff[i*4+3] = (uint8_t)dwrd[i];
    }

    /* Even-Odd nominal page 순서 (0, 1) 및 Alert flag (0, 0) 검사 */
    if (getbitu(buff, 0, 1) != 0 || getbitu(buff + 16, 0, 1) != 1) return 0;
    if (getbitu(buff, 1, 1) != 0 || getbitu(buff + 16, 1, 1) != 0) return 0;

    /* CRC24Q 검증: Even 114 bit + Odd 82 bit (4 bit pad 포함 25 byte) */
    for (i = 0, j = 4; i < 15; i++, j += 8) setbitu(crc_buff, j, 8, getbitu(buff, i*8, 8));
    for (i = 0, j = 118; i < 11; i++, j += 8) setbitu(crc_buff, j, 8, getbitu(buff + 16, i*8, 8));

    if (rtk_crc24q(crc_buff, 25) != getbitu(buff + 16, 82, 24)) {
        return 0; /* CRC 에러 패킷 폐기 */
    }
    return 1;
}

int32_t lnis_pvt_navigation(void *context, int32_t sys, int32_t prn,
    int32_t observation_week, const uint32_t *words, uint32_t count) {
    lnis_pvt_context *ctx = context;
    if (!ctx || !words || observation_week < 0 || observation_week > 8191) return -1;

    if (sys == LNIS_SYS_GPS) {
        uint8_t block[30];
        int i, id, sat, decoded_week, shift;
        eph_t eph = {0};
        if (count != 10 || prn < 1 || prn > 32) return -1;
        for (i = 0; i < 10; i++) {
            if (words[i] > 0xffffffu) return -1;
            block[i*3]   = (uint8_t)(words[i] >> 16);
            block[i*3+1] = (uint8_t)(words[i] >> 8);
            block[i*3+2] = (uint8_t)words[i];
        }
        if (block[0] != 0x8b) return -1;
        id = (block[5] >> 2) & 7;
        if (id < 1 || id > 5) return -1;
        memcpy(ctx->gps_frames[prn-1] + (id-1)*30, block, 30);
        if (id == 4) decode_frame(ctx->gps_frames[prn-1], NULL, NULL, ctx->nav.ion_gps, NULL);
        if (id > 3 || !decode_frame(ctx->gps_frames[prn-1], &eph, NULL, NULL, NULL)) return 0;
        time2gpst(eph.ttr, &decoded_week);
        shift = (int)floor((observation_week - decoded_week + 512.0) / 1024.0) * 1024;
        eph.week += shift;
        eph.toe = timeadd(eph.toe, shift * 604800.0);
        eph.toc = timeadd(eph.toc, shift * 604800.0);
        eph.ttr = timeadd(eph.ttr, shift * 604800.0);
        sat = satno(SYS_GPS, prn);
        if (sat <= 0 || sat > MAXSAT) return -1;
        eph.sat = sat;
        ctx->nav.eph[sat-1] = eph;
        return 1;
    }
    else if (sys == LNIS_SYS_BDS) {
        uint8_t block[38] = {0};
        int i, frn, sat, decoded_week, shift;
        eph_t eph = {0};
        if (count != 10 || prn < 1 || prn > 63) return -1;
        if (prn <= 5 || prn >= 59) return 0; /* GEO D2 NAV는 이번 범위에서 제외 */
        for (i = 0; i < 10; i++) {
            if (words[i] > 0x3fffffffu) return -1;
            setbitu(block, i * 30, 30, words[i]);
        }
        frn = getbitu(block, 15, 3);
        if (frn < 1 || frn > 5) return -1;
        memcpy(ctx->bds_frames[prn-1] + (frn-1)*38, block, 38);
        if (frn == 1) decode_bds_d1(ctx->bds_frames[prn-1], NULL, ctx->nav.ion_cmp, NULL);
        if (frn > 3 || !decode_bds_d1(ctx->bds_frames[prn-1], &eph, NULL, NULL)) return 0;
        time2gpst(eph.ttr, &decoded_week);
        shift = (int)floor((observation_week - decoded_week + 512.0) / 1024.0) * 1024;
        eph.week += shift;
        eph.toe = timeadd(eph.toe, shift * 604800.0);
        eph.toc = timeadd(eph.toc, shift * 604800.0);
        eph.ttr = timeadd(eph.ttr, shift * 604800.0);
        sat = satno(SYS_CMP, prn);
        if (sat <= 0 || sat > MAXSAT) return -1;
        eph.sat = sat;
        ctx->nav.eph[sat-1] = eph;
        return 1;
    }
    else if (sys == LNIS_SYS_GAL) {
        uint8_t buff[32] = {0};
        int i, j, k, type, sat, decoded_week, shift;
        eph_t eph = {0};
        if (count != 8 || prn < 1 || prn > 36) return -1;
        if (!unpack_gal_inav(words, buff)) return 0;

        type = getbitu(buff, 2, 6); /* word type (bits 2..7 of even page) */
        if (type < 1 || type > 5) return 0;

        /* Word 2는 서브프레임의 시작(t=0s, 14s)이므로 새 주기 플래그 초기화 */
        if (type == 2) ctx->gal_frames[prn-1][112] = 0;

        /* 128-bit (112 bit from Even, 16 bit from Odd) 추출하여 프레임 버퍼에 저장 */
        k = type * 16;
        for (i = 0, j = 2; i < 14; i++, j += 8) ctx->gal_frames[prn-1][k++] = (uint8_t)getbitu(buff, j, 8);
        for (i = 0, j = 2; i <  2; i++, j += 8) ctx->gal_frames[prn-1][k++] = (uint8_t)getbitu(buff + 16, j, 8);

        /* word 수신 플래그 기록 (bit 1~5) */
        ctx->gal_frames[prn-1][112] |= (1 << type);

        /* Word 5 수신 시 이온층 파라미터 디코딩 */
        if (type == 5) {
            decode_gal_inav(ctx->gal_frames[prn-1], NULL, ctx->nav.ion_gal, NULL);
        }

        /* Word 1, 2, 3, 4, 5 (마스크 0x3E)가 모두 온전히 수신되었을 때만 에페머리스 디코딩 */
        if ((ctx->gal_frames[prn-1][112] & 0x3E) != 0x3E) return 0;
        if (!decode_gal_inav(ctx->gal_frames[prn-1], &eph, NULL, NULL)) return 0;

        /* 에페머리스 완성 후 플래그 초기화하여 중복/오염 디코딩 방지 */
        ctx->gal_frames[prn-1][112] = 0;

        time2gpst(eph.ttr, &decoded_week);
        shift = (int)floor((observation_week - decoded_week + 512.0) / 1024.0) * 1024;
        eph.week += shift;
        eph.toe = timeadd(eph.toe, shift * 604800.0);
        eph.toc = timeadd(eph.toc, shift * 604800.0);
        eph.ttr = timeadd(eph.ttr, shift * 604800.0);
        sat = satno(SYS_GAL, prn);
        if (sat <= 0 || sat > MAXSAT) return -1;
        eph.sat = sat;
        ctx->nav.eph[sat-1] = eph;
        return 1;
    }
    return -1;
}

int32_t lnis_pvt_gps_navigation(void *context, int32_t prn,
    int32_t observation_week, const uint32_t *words, uint32_t count) {
    return lnis_pvt_navigation(context, LNIS_SYS_GPS, prn, observation_week, words, count);
}

int32_t lnis_pvt_solve(void *context, int32_t navsys, int32_t week, double tow,
    const double *observations, uint32_t count, double *result,
    char *message, uint32_t message_size) {
    lnis_pvt_context *ctx = context;
    obsd_t obs[MAXOBS] = {{0}};
    sol_t sol = {0};
    prcopt_t opt = prcopt_default;
    char error[128] = {0};
    uint32_t i;
    int ok;
    if (!ctx || !observations || !result || !message || message_size < 128 ||
        count < 1 || count > MAXOBS || week < 0 || week > 8191 ||
        !isfinite(tow) || tow < 0 || tow >= 604800) return -1;
    memset(result, 0, 9 * sizeof(double));
    message[0] = 0;
    for (i = 0; i < count; i++) {
        double sys_d = observations[i * 5 + 0];
        double prn   = observations[i * 5 + 1];
        double range = observations[i * 5 + 2];
        double doppler = observations[i * 5 + 3];
        double cno   = observations[i * 5 + 4];
        uint32_t j;
        int sys = (int)sys_d;
        if (!isfinite(sys_d) || !isfinite(prn) || prn < 1 || prn != floor(prn) ||
            !isfinite(range) || range <= 0 || !isfinite(doppler) ||
            !isfinite(cno) || cno < 0 || cno > 100) return -1;
        int sat = satno(sys, (int)prn);
        if (sat <= 0 || sat > MAXSAT) return -1;
        for (j = 0; j < i; j++) {
            if ((int)observations[j * 5 + 0] == sys && (int)observations[j * 5 + 1] == (int)prn) return -1;
        }
        obs[i].time = gpst2time(week, tow);
        obs[i].sat = (uint8_t)sat;
        obs[i].rcv = 1;
        if (sys == SYS_CMP) {
            obs[i].code[0] = CODE_L2I;
        } else if (sys == SYS_GAL) {
            obs[i].code[0] = CODE_L1B;
        } else {
            obs[i].code[0] = CODE_L1C;
        }
        obs[i].P[0] = range;
        obs[i].D[0] = (float)doppler;
        obs[i].SNR[0] = (uint16_t)(cno / SNR_UNIT);
    }
    opt.navsys = navsys;
    opt.err[1] = opt.err[2] = 0.03;
    opt.ionoopt = IONOOPT_BRDC;
    opt.tropopt = TROPOPT_SAAS;
    opt.elmin = 15.0 * D2R;
    ok = pntpos(obs, (int)count, &ctx->nav, &opt, &sol, NULL, NULL, error);
    snprintf(message, message_size, "%s", error);
    if (!ok) return 0;
    for (i = 0; i < 6; i++) result[i] = sol.rr[i];
    if (navsys == SYS_CMP) {
        result[6] = sol.dtr[0] + sol.dtr[3];
    } else if (navsys == SYS_GAL) {
        result[6] = sol.dtr[0] + sol.dtr[2];
    } else {
        result[6] = sol.dtr[0];
    }
    result[7] = sol.ns;
    result[8] = sol.qv[0] > 0 && sol.qv[1] > 0 && sol.qv[2] > 0;
    return 1;
}

int32_t lnis_pvt_gps_solve(void *context, int32_t week, double tow,
    const double *observations, uint32_t count, double *result,
    char *message, uint32_t message_size) {
    if (!observations || count < 1 || count > 32) return -1;
    double obs5[32 * 5];
    uint32_t i;
    for (i = 0; i < count; i++) {
        obs5[i * 5 + 0] = (double)LNIS_SYS_GPS;
        obs5[i * 5 + 1] = observations[i * 4 + 0];
        obs5[i * 5 + 2] = observations[i * 4 + 1];
        obs5[i * 5 + 3] = observations[i * 4 + 2];
        obs5[i * 5 + 4] = observations[i * 4 + 3];
    }
    return lnis_pvt_solve(context, LNIS_SYS_GPS, week, tow, obs5, count, result, message, message_size);
}
