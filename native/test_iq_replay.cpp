// Exercise the production replay gate with consumers running at different speeds.
#include <cassert>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <thread>
#include <vector>

#define LNIS_IQ_RECEIVER
#define LNIS_REPLAY_STALL_MS 1000
#define SDR_MAX_NCH 3
#define MAX_BUFF 8000

struct test_channel { int N, prn; };
struct sdr_ch_th_t { test_channel *ch; int64_t completed_ix; };
struct sdr_rcv_t {
    int state;
    int nch, N, file_error, file_stop;
    sdr_ch_th_t *th[SDR_MAX_NCH];
};

static uint32_t sdr_get_tick()
{
    using namespace std::chrono;
    return (uint32_t)duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

static void sdr_sleep_msec(int ms)
{
    std::this_thread::sleep_for(std::chrono::milliseconds(ms));
}

#include "iq_file_replay.h"

int main()
{
    test_channel channels[] = {{2, 8}, {3, 23}, {5, 29}};
    sdr_ch_th_t threads[3] = {};
    sdr_rcv_t receiver = {1, 3, 1, 0, 0, {}};
    int64_t produced = 0;
    int ring[MAX_BUFF];
    std::vector<std::thread> workers;
    const int total = MAX_BUFF * 3 + 7; // wrap the buffer repeatedly, with an incomplete final window
    for (int i = 0; i < 3; i++) {
        threads[i].ch = &channels[i];
        receiver.th[i] = &threads[i];
        workers.emplace_back([&, i] {
            int n = channels[i].N;
            for (int64_t next = 0; next + 2 * n <= total - 1; next += n) {
                while (__atomic_load_n(&produced, __ATOMIC_ACQUIRE) < next + 2 * n) {
                    sdr_sleep_msec(1);
                }
                if (next % (n * 37) == 0) sdr_sleep_msec(i + 1);
                for (int j = 0; j < 2 * n; j++) {
                    assert(ring[(next + j) % MAX_BUFF] == next + j);
                }
                __atomic_store_n(&threads[i].completed_ix, next + n, __ATOMIC_RELEASE);
            }
        });
    }
    for (int i = 0; i < total; i++) {
        assert(wait_file_channels(&receiver, __atomic_load_n(&produced, __ATOMIC_ACQUIRE), 0));
        ring[i % MAX_BUFF] = i;
        __atomic_store_n(&produced, (int64_t)i, __ATOMIC_RELEASE);
    }
    assert(wait_file_channels(&receiver, total - 1, 1));
    for (auto &worker : workers) worker.join();
    for (int i = 0; i < 3; i++) {
        int n = channels[i].N;
        assert(threads[i].completed_ix == ((total - 1 - 2 * n) / n + 1) * n);
    }
    puts("PASS: slow consumers, ring wrap, full EOF drain, partial final correlation window");

    receiver.nch = 1;
    threads[0].completed_ix = 0;
    assert(wait_file_channels(&receiver, 3, 1)); // fewer than 2*n cycles needs no work
    assert(!wait_file_channels(&receiver, 4, 1)); // deliberately stalled channel
    assert(receiver.file_error == 1);
    puts("PASS: a stalled consumer fails instead of silently completing");

    receiver.file_error = 0;
    std::thread cancel([&] { sdr_sleep_msec(10); __atomic_store_n(&receiver.file_stop, 1, __ATOMIC_RELEASE); });
    assert(!wait_file_channels(&receiver, 100, 0));
    cancel.join();
    assert(receiver.file_error == 0);
    puts("PASS: cancellation interrupts backpressure without waiting for timeout");
    return 0;
}
