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
    patch --batch --forward --fuzz=0 -p1 < "$p"
done
# Expose exactly the patched files for maintenance; keep originals only in vendor.
mkdir -p /out/modified-sources
cp --parents LANS-AFS-SIM/afs_nav.c LANS-AFS-SIM/afs_sim.c \
    PocketSDR-AFS/src/sdr_ldpc_afs.c /out/modified-sources/
lans=LANS-AFS-SIM
rtk=PocketSDR-AFS/lib/RTKLIB/src
common=(-O2 -ffunction-sections -fdata-sections -I. -I"$rtk" -I"$lans" -I"$lans/pocketsdr")
rtk_sources=()
for f in rtkcmn rcvraw pntpos ephemeris preceph sbas ionex; do rtk_sources+=("$rtk/$f.c"); done
case "${1:-}" in
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
*) echo 'Usage: build.sh iq|codec (inside the build container)' >&2; exit 2 ;;
esac
# Prove the checked-in inputs were not patched in place.
cd /src/vendor
sha256sum --strict -c ../UPSTREAM-SHA256.txt > /dev/null
