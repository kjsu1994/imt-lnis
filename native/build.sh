#!/usr/bin/env bash
set -euo pipefail
# Sources are immutable. Only this disposable container workspace is patched.
cd /src/vendor
sha256sum --strict -c ../UPSTREAM-SHA256.txt
cd /src
mkdir -p /work /out/verification
cp -R vendor/. /work/
cp ./*.c ./*.h /work/
cd /work
# Normalize only build copies so patches behave identically on Windows and Linux.
find . -type f \( -name '*.c' -o -name '*.h' \) -exec sed -i 's/\r$//' {} +
for p in /src/patches/*.patch; do
    # The complete receiver uses upstream's full header, not the codec-only shim.
    if [[ "${1:-}" == receiver && "$p" == */03-decoder-includes.patch ]]; then continue; fi
    if [[ "${1:-}" != receiver && "$p" == */06-file-replay.patch ]]; then continue; fi
    patch --batch --forward --fuzz=0 -p1 < "$p"
done
# Expose exactly the patched files for maintenance; keep originals only in vendor.
mkdir -p /out/modified-sources
cp --parents LANS-AFS-SIM/afs_nav.c LANS-AFS-SIM/afs_nav.h LANS-AFS-SIM/afs_sim.c /out/modified-sources/
if [[ "${1:-}" != receiver ]]; then
    cp --parents PocketSDR-AFS/src/sdr_ldpc_afs.c /out/modified-sources/
fi
lans=LANS-AFS-SIM
rtk=PocketSDR-AFS/lib/RTKLIB/src
common=(-O2 -ffunction-sections -fdata-sections -I. -I"$rtk" -I"$lans" -I"$lans/pocketsdr")
rtk_sources=()
for f in rtkcmn rcvraw pntpos ephemeris preceph sbas ionex; do rtk_sources+=("$rtk/$f.c"); done
case "${1:-}" in
receiver)
    mkdir -p /out/iq /work/receiver-objects
    g++ -O2 -pthread -I/work /src/test_iq_replay.cpp -o /out/verification/test_iq_replay
    /out/verification/test_iq_replay > /out/verification/file-replay.txt
    cd /work/receiver-objects
    # All SDR originals are compiled together; unused sections are discarded.
    gcc -O2 -ffunction-sections -fdata-sections -DLNIS_IQ_RECEIVER -DSVR_REUSEADDR \
        -I"/work/$rtk" -c /work/"$rtk"/*.c
    gcc -O2 -I/work/LDPC-codes '-DRAND_FILE="/app/iq/randfile"' -c /work/LDPC-codes/*.c
    g++ -O2 -ffunction-sections -fdata-sections -DLNIS_IQ_RECEIVER \
        -I"/work/$rtk" -I/work/PocketSDR-AFS/src -c \
        /work/PocketSDR-AFS/src/*.c /work/PocketSDR-AFS/app/pocket_trk/pocket_trk.c
    g++ -Wl,--gc-sections -o /out/iq/pocket_trk *.o -lfftw3f -lusb-1.0 -lfec -lm -lpthread
    cp /work/LDPC-codes/randfile /out/iq/
    cd /work
    cp iq_file_replay.h /out/modified-sources/
    cp --parents PocketSDR-AFS/app/pocket_trk/pocket_trk.c \
        PocketSDR-AFS/src/pocket_sdr.h PocketSDR-AFS/src/sdr_pvt_afs.c \
        PocketSDR-AFS/src/sdr_ch.c PocketSDR-AFS/src/sdr_nav.c \
        PocketSDR-AFS/src/sdr_rcv.c PocketSDR-AFS/src/sdr_pvt.c PocketSDR-AFS/src/sdr_func.c \
        "$rtk/rtklib.h" /out/modified-sources/
    ;;
iq)
    mkdir -p /out/iq
    gcc "${common[@]}" -static -fopenmp -Wl,--gc-sections -I"$lans/ldpc" \
        "$lans/afs_sim.c" iq_earth.c "$lans/afs_nav.c" "$lans/afs_rand.c" \
        "$lans/ldpc/alloc.c" "$lans/ldpc/mod2sparse.c" \
        "$rtk/rtkcmn.c" "$rtk/rcvraw.c" "$rtk/ephemeris.c" "$lans/pocketsdr/pocketsdr.c" -lm -o /out/iq/afs_sim
    cp "$lans/008_Weil1500hex210prns.txt" "$lans/default_almanac.txt" "$lans/LICENSE.txt" /out/iq/
    cd /out/iq
    if ./afs_sim -t 0.1 -b 2 /work/missing-input.bin > missing-input.log 2>&1; then
        echo 'ERROR: I/Q accepted missing GNSS input' >&2; exit 1
    fi
    grep -q 'Earth input requires valid PVT' missing-input.log
    rm missing-input.log
    ;;
codec)
    mkdir -p /out/native-linux /out/native-pvt
    ldpc_sources=()
    for f in rcode channel dec enc alloc intio blockio check open mod2dense mod2sparse mod2convert distrib rand; do
        ldpc_sources+=("LDPC-codes/$f.c")
    done
    sources=(lnis_afs_codec.c lnis_pvt.c "$lans/afs_nav.c" "$lans/pocketsdr/pocketsdr.c"
        PocketSDR-AFS/src/sdr_ldpc_afs.c "${rtk_sources[@]}" "${ldpc_sources[@]}")
    common+=(-ILDPC-codes '-DRAND_FILE="./randfile"')
    gcc "${common[@]}" -fPIC -fvisibility=hidden -pthread -shared -Wl,--gc-sections,-z,defs \
        "${sources[@]}" -lm -o /out/native-linux/libLnisAfsCodec.so
    gcc "${common[@]}" -pthread -Wl,--gc-sections test_pvt.c "${rtk_sources[@]}" -lm -o /out/verification/test_pvt
    gcc -O2 -I. test_afs.c -L/out/native-linux -lLnisAfsCodec '-Wl,-rpath,$ORIGIN/../native-linux' -o /out/verification/test_afs
    /out/verification/test_pvt > /out/verification/linux-pvt.txt
    /out/verification/test_afs > /out/verification/linux-afs.txt
    win=(x86_64-w64-mingw32-gcc "${common[@]}" -DWIN32 -static -Wl,--gc-sections)
    "${win[@]}" -shared "${sources[@]}" -Wl,--out-implib,/work/libLnisAfsCodec.a -lwinmm -lws2_32 -lm -o /out/native-pvt/LnisAfsCodec.dll
    "${win[@]}" test_pvt.c "${rtk_sources[@]}" -lwinmm -lws2_32 -lm -o /out/native-pvt/test_pvt.exe
    "${win[@]}" test_afs.c /work/libLnisAfsCodec.a -o /out/native-pvt/test_afs.exe
    gcc --version > /out/verification/toolchain.txt
    x86_64-w64-mingw32-gcc --version >> /out/verification/toolchain.txt
    ;;
*) echo 'Usage: build.sh iq|codec|receiver (inside the build container)' >&2; exit 2 ;;
esac
# Prove the checked-in inputs were not patched in place.
cd /src/vendor
sha256sum --strict -c ../UPSTREAM-SHA256.txt > /dev/null
