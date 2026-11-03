/* LNIS-owned check for the patched generator's range and Doppler input boundary. */
#include "iq_earth.c"
#include <assert.h>

int main(int argc, char **argv) {
    int week;
    double tow, xyz[3], llh[3];
    assert(argc == 2);
    int count = iq_earth_load(argv[1], &week, &tow, xyz, llh);
    assert(count >= 4);
    for (int i = 0; i < 32; i++) {
        if (!selected[i]) continue;
        double first = iq_earth_range(i + 1, week, tow);
        double next = iq_earth_range(i + 1, week, tow + 0.1);
        double doppler = -(next - first) / 0.1 / (CLIGHT / FREQ1);
        assert(fabs(first - anchor_range[i]) < 1e-6);
        assert(fabs(doppler - anchor_doppler[i]) < 1e-4);
        double later = iq_earth_range(i + 1, week, tow + 30);
        double expected = model_range(i + 1, week, tow + 30)
                + range_offset[i] + rate_offset[i] * 30;
        assert(isfinite(later) && later > 0);
        assert(fabs(later - expected) < 1e-6);
        printf("PRN %d: range %.9f m Doppler %.9f Hz; anchor and evolution PASS\n",
                i + 1, first, doppler);
    }
    printf("PASS: %d observed PRNs, original 0.1 s modulation interval\n", count);
    return 0;
}
