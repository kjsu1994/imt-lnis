FROM eclipse-temurin:21-jre-noble

WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends libfftw3-single3 libusb-1.0-0 libfec0 \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 lnis && mkdir -p /app/data /app/native \
    && chown -R 10001:10001 /app
COPY lnis.jar /app/lnis.jar
COPY native/libLnisAfsCodec.so /app/native/libLnisAfsCodec.so
COPY iq /app/iq
RUN chmod 755 /app/iq/afs_sim /app/iq/pocket_trk
COPY licenses /app/licenses
USER 10001
EXPOSE 8088
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-Djna.tmpdir=/tmp","-Djava.io.tmpdir=/tmp","-jar","/app/lnis.jar","node"]
