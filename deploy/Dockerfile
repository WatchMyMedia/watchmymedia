# syntax=docker/dockerfile:1
# Build context is the repository root:
#   docker build -f deploy/Dockerfile -t watchmymedia:dev .

# ---- web ---------------------------------------------------------------
FROM --platform=$BUILDPLATFORM node:24-alpine AS web
WORKDIR /src/web
RUN npm install -g pnpm@9
COPY web/package.json web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY web/ ./
RUN pnpm build

# ---- server ------------------------------------------------------------
# Built on the host platform and cross-compiled, so multi-arch images do
# not need emulation for the Go build.
FROM --platform=$BUILDPLATFORM golang:1.27-alpine AS server
WORKDIR /src/server
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
COPY --from=web /src/web/dist ./internal/webui/dist
ARG VERSION=dev
ARG TARGETOS
ARG TARGETARCH
# The OpenSubtitles API key comes in as a build secret (not an ARG, which
# the image history would keep); without it the image has no online subtitles.
RUN --mount=type=secret,id=opensubtitles_api_key,required=false \
    CGO_ENABLED=0 GOOS=${TARGETOS} GOARCH=${TARGETARCH} go build -trimpath \
    -ldflags="-s -w -X main.version=${VERSION} -X main.openSubtitlesKey=$(cat /run/secrets/opensubtitles_api_key 2>/dev/null)" \
    -o /out/watchmymedia ./cmd/server

# ---- runtime -----------------------------------------------------------
FROM debian:trixie-slim
LABEL org.opencontainers.image.title="WatchMyMedia" \
      org.opencontainers.image.description="Self-hosted media server" \
      org.opencontainers.image.source="https://github.com/WatchMyMedia/watchmymedia"

# ffmpeg is jellyfin-ffmpeg, the build the Mac and Windows releases ship
# (deploy/macos/fetch-ffmpeg.sh pins the same version; bump both together),
# from Jellyfin's apt repository. It carries the user-mode GPU drivers
# itself: Intel's iHD and i965 VA-API drivers and the oneVPL runtime for
# QSV, Mesa's VA-API driver for AMD, and the Vulkan loaders, under
# /usr/lib/jellyfin-ffmpeg. It also has the filters the GPU pipelines use
# (tonemap_cuda, vpp_qsv tone mapping) that Debian's ffmpeg lacks. NVIDIA's
# libraries are not in any image: the NVIDIA container runtime mounts the
# host's driver when the container is started with it.
ARG JELLYFIN_FFMPEG_VERSION=8.1.3-2
ARG JELLYFIN_SIGNING_FINGERPRINT=4918AABC486CA052358D778D49023CD01DE21A7B
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates tzdata curl gnupg \
 && install -d -m 0755 /etc/apt/keyrings \
 && curl -fsSL https://repo.jellyfin.org/jellyfin_team.gpg.key -o /tmp/jellyfin.asc \
 && gpg --show-keys --with-colons /tmp/jellyfin.asc | grep -q "^fpr:::::::::${JELLYFIN_SIGNING_FINGERPRINT}:" \
 && gpg --dearmor -o /etc/apt/keyrings/jellyfin.gpg /tmp/jellyfin.asc \
 && printf 'Types: deb\nURIs: https://repo.jellyfin.org/debian\nSuites: trixie\nComponents: main\nArchitectures: %s\nSigned-By: /etc/apt/keyrings/jellyfin.gpg\n' \
      "$(dpkg --print-architecture)" > /etc/apt/sources.list.d/jellyfin.sources \
 && apt-get update \
 && apt-get install -y --no-install-recommends "jellyfin-ffmpeg8=${JELLYFIN_FFMPEG_VERSION}-trixie" \
 && apt-get purge -y --auto-remove curl gnupg \
 && rm -rf /var/lib/apt/lists/* /tmp/jellyfin.asc \
 # On PATH for people: docker exec watchmymedia vainfo, ffmpeg -encoders.
 && ln -s /usr/lib/jellyfin-ffmpeg/ffmpeg /usr/lib/jellyfin-ffmpeg/ffprobe /usr/lib/jellyfin-ffmpeg/vainfo /usr/local/bin/
COPY --from=server /out/watchmymedia /usr/local/bin/watchmymedia
COPY deploy/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENV WATCHMYMEDIA_DATA_DIR=/config \
    WATCHMYMEDIA_TRANSCODE_DIR=/transcode \
    WATCHMYMEDIA_MOVIES_PATH=/movies \
    WATCHMYMEDIA_TV_PATH=/tv \
    WATCHMYMEDIA_PORT=27587 \
    WATCHMYMEDIA_LOG_FORMAT=json \
    WATCHMYMEDIA_INSTALL=docker \
    WATCHMYMEDIA_FFMPEG_PATH=/usr/lib/jellyfin-ffmpeg/ffmpeg \
    WATCHMYMEDIA_FFPROBE_PATH=/usr/lib/jellyfin-ffmpeg/ffprobe
# What the NVIDIA container runtime exposes when it runs this container
# (NVENC and NVDEC need video and compute; utility is nvidia-smi). Inert
# without the runtime. Which GPU is NVIDIA_VISIBLE_DEVICES, left to the
# compose file or the Unraid template.
ENV NVIDIA_DRIVER_CAPABILITIES=compute,video,utility
VOLUME ["/config", "/transcode"]
EXPOSE 27587
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
  CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/27587 && printf 'GET /api/health HTTP/1.0\r\n\r\n' >&3 && head -1 <&3 | grep -q 200"]
ENTRYPOINT ["/entrypoint.sh"]
CMD ["watchmymedia"]
