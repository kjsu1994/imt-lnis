#include "lnis_pvt.h"
#include "rtklib.h"

/* 직접 작성한 연결 코드다. 항법 해석과 위치/속도 알고리즘은 원본 RTKLIB를 호출한다.
 * 컨텍스트는 시험마다 새로 생성하며 다른 시험의 항법 캐시를 공유하지 않는다. */
typedef struct {
    nav_t nav;
    uint8_t gps_frames[32][150];
1    uint8_t bds_frames[64][190];
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

/* Galileo UBX-RXM-SFRBX 8 word (Even 128 bit + Odd 128 bit)에서
 * RTKLIB decode_gal_inav가 기대하는 128-bit (16 byte) 단일 I/NAV word를 추출한다. */
static void pack_gal_inav(const uint32_t *dwrd, uint8_t *data) {
    uint8_t even[16] = {0}, odd[16] = {0};
    int i;
    for (i = 0; i < 4; i++) {
        even[i*4]   = (uint8_t)(dwrd[i] >> 24);
        even[i*4+1] = (uint8_t)(dwrd[i] >> 16);
        even[i*4+2] = (uint8_t)(dwrd[i] >> 8);
        even[i*4+3] = (uint8_t)dwrd[i];
    }
    for (i = 0; i < 4; i++) {
        odd[i*4]   = (uint8_t)(dwrd[i+4] >> 24);
        odd[i*4+1] = (uint8_t)(dwrd[i+4] >> 16);
        odd[i*4+2] = (uint8_t)(dwrd[i+4] >> 8);
        odd[i*4+3] = (uint8_t)dwrd[i+4];
    }
    memset(data, 0, 16);
    /* Even half-page: bit 2부터 112 bit 복사 (앞 2 bit는 sync/page bit 제외) */
    for (i = 0; i < 112; i++) {
        int b = (even[(i + 2) / 8] >> (7 - ((i + 2) % 8))) & 1;
        if (b) data[i / 8] |= (1 << (7 - (i % 8)));
    }
    /* Odd half-page: bit 2부터 16 bit 복사하여 data 112..127 bit에 연결 */
    for (i = 0; i < 16; i++) {
        int b = (odd[(i + 2) / 8] >> (7 - ((i + 2) % 8))) & 1;
        int dst = 112 + i;
        if (b) data[dst / 8] |= (1 << (7 - (dst % 8)));
    }
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
        uint8_t data[16] = {0};
        int type, sat, decoded_week, shift;
        eph_t eph = {0};
        if (count != 8 || prn < 1 || prn > 36) return -1;
        /* RTKLIB ubx.c decode_enav와 동일: even(0) + odd(1) 순서의 nominal page만 사용 */
        if ((words[0] >> 31) != 0 || (words[4] >> 31) != 1) return 0;
        if (((words[0] >> 30) & 1) || ((words[4] >> 30) & 1)) return 0; /* alert page */
        pack_gal_inav(words, data);
        type = getbitu(data, 0, 6);
        if (type < 1 || type > 5) return 0;
        memcpy(ctx->gal_frames[prn-1] + 16 * type, data, 16);
        if (type == 5) decode_gal_inav(ctx->gal_frames[prn-1], NULL, ctx->nav.ion_gal, NULL);
        if (!decode_gal_inav(ctx->gal_frames[prn-1], &eph, NULL, NULL)) return 0;
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
