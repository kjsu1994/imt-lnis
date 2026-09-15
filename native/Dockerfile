# Same pinned GCC image as the existing I/Q generator, preserving its waveform.
FROM gcc:14@sha256:3ae7320d7dd41f446a48930e1edf4e7a41c7be5ae43a5ddbf017c53fe6495738 AS iq
WORKDIR /src
COPY . .
RUN --network=none bash build.sh iq

# Match the runtime's Ubuntu 24.04 ABI; never build the shared library on a newer libc.
FROM ubuntu:24.04@sha256:224a1869083a311ef3f13648a154ba79832fbef6364d31493642ca03082da254 AS codec
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc=4:13.2.0-7ubuntu1 gcc-13=13.3.0-6ubuntu2~24.04.1 \
    gcc-13-x86-64-linux-gnu=13.3.0-6ubuntu2~24.04.1 \
    libc6-dev=2.39-0ubuntu8.9 binutils=2.42-4ubuntu2.10 patch=2.7.6-7build3 \
    gcc-mingw-w64-x86-64=13.2.0-6ubuntu1+26.1 \
    gcc-mingw-w64-x86-64-win32=13.2.0-6ubuntu1+26.1 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY . .
RUN --network=none bash build.sh codec

FROM scratch AS artifacts
COPY --from=iq /out/iq /iq
COPY --from=codec /out/native-linux /native-linux
COPY --from=codec /out/native-pvt /native-pvt
COPY --from=codec /out/verification /verification
COPY --from=codec /out/modified-sources /modified-sources
