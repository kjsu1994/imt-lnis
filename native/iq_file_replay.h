#ifndef LNIS_IQ_FILE_REPLAY_H
#define LNIS_IQ_FILE_REPLAY_H

#ifdef LNIS_IQ_RECEIVER
/* LNIS 변경: 파일 입력에만 적용하는 생산자/소비자 대기.
 * 작은 선행 읽기 구간은 빠른 디스크에서도 탐색 채널을 과도하게 추월하지 않는다.
 * EOF에서는 상관 계산에 필요한 2*n 샘플이 남은 채널을 끝까지 처리한다. */
#define FILE_READ_AHEAD 100     // 100 x SDR_CYC (normally 100 ms)
#ifndef LNIS_REPLAY_STALL_MS
#define LNIS_REPLAY_STALL_MS 30000
#endif

// produced는 블록 개수가 아닌 원본 get_buff_ix()의 공개된 마지막 인덱스다.
static int wait_file_channels(sdr_rcv_t *rcv, int64_t produced, int drain)
{
    int64_t previous[SDR_MAX_NCH];
    uint32_t changed[SDR_MAX_NCH];
    for (int i = 0; i < rcv->nch; i++) {
        previous[i] = __atomic_load_n(&rcv->th[i]->completed_ix, __ATOMIC_ACQUIRE);
        changed[i] = sdr_get_tick();
    }
    while (!__atomic_load_n(&rcv->file_stop, __ATOMIC_ACQUIRE)) {
        int waiting = 0;
        for (int i = 0; i < rcv->nch; i++) {
            sdr_ch_th_t *th = rcv->th[i];
            int n = th->ch->N / rcv->N;
            int64_t completed = __atomic_load_n(&th->completed_ix, __ATOMIC_ACQUIRE);
            int ahead = FILE_READ_AHEAD > 2 * n ? FILE_READ_AHEAD : 2 * n;
            if (n <= 0 || ahead >= MAX_BUFF) {
                fprintf(stderr, "I/Q replay invalid channel window: PRN=%d\n", th->ch->prn);
                __atomic_store_n(&rcv->file_error, 1, __ATOMIC_RELEASE);
                return 0;
            }
            int pending = drain ? completed + 2 * n <= produced : produced - completed >= ahead;
            uint32_t now = sdr_get_tick();
            if (!pending || completed != previous[i]) {
                previous[i] = completed;
                changed[i] = now;
            }
            if (!pending) continue;
            waiting = 1;
            if ((uint32_t)(now - changed[i]) >= LNIS_REPLAY_STALL_MS) {
                fprintf(stderr, "I/Q replay stalled: PRN=%d produced=%lld completed=%lld drain=%d\n",
                    th->ch->prn, (long long)produced, (long long)completed, drain);
                __atomic_store_n(&rcv->file_error, 1, __ATOMIC_RELEASE);
                return 0;
            }
        }
        if (!waiting) return 1;
        sdr_sleep_msec(1);
    }
    return 0; // cancellation, never report a drained file
}
#endif

#endif
