FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY . .
RUN bash ./gradlew :app:installDist --no-daemon

FROM eclipse-temurin:25-jre
RUN groupadd --gid 10001 logh7 && useradd --uid 10001 --gid logh7 --no-create-home logh7 \
    && mkdir -p /data/sessions /data/captures && chown -R logh7:logh7 /data
WORKDIR /app
COPY --from=build --chown=logh7:logh7 /build/app/build/install/app/ /app/
ENV LOGH7_SESSION_STORE_DIR=/data/sessions \
    LOGH7_CAPTURE_DIR=/data/captures
USER 10001:10001
EXPOSE 47900 47902 47903
HEALTHCHECK --interval=5s --timeout=3s --start-period=10s --retries=20 \
    CMD bash -ec 'exec 3<>/dev/tcp/127.0.0.1/47901; printf "GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n" >&3; IFS= read -r status <&3; [[ "$status" == *" 200 "* ]]'
# Gateway rejects wildcard/public bind addresses. Resolve the container private IP
# only when no explicit bind address was supplied; retain the application's guard.
ENTRYPOINT ["bash", "-ec", "if [[ -z ${LOGH7_BIND_ADDRESS:-} ]]; then read -ra addresses <<< \"$(hostname -i)\"; export LOGH7_BIND_ADDRESS=${addresses[0]}; fi; exec /app/bin/app"]
