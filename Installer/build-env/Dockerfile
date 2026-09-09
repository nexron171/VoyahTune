# Build-only toolchains. None of these packages is required by the end user.
ARG NODE_IMAGE=node:22-bookworm-slim
ARG UBUNTU_IMAGE=ubuntu:22.04
FROM ${NODE_IMAGE} AS node
FROM ${UBUNTU_IMAGE}
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential curl ca-certificates pkg-config file git unzip xz-utils python3 \
    libwebkit2gtk-4.1-dev libappindicator3-dev librsvg2-dev patchelf \
    libssl-dev libsoup-3.0-dev libgtk-3-dev libfuse2 fuse squashfs-tools \
    gstreamer1.0-plugins-base gstreamer1.0-plugins-good \
    cmake ninja-build clang llvm lld nsis libusb-1.0-0-dev libprotobuf-dev protobuf-compiler \
    liblz4-dev libzstd-dev libbrotli-dev libpcre2-dev libgtest-dev golang p7zip-full xvfb xauth \
    && rm -rf /var/lib/apt/lists/*
COPY --from=node /usr/local/ /usr/local/
ENV CARGO_HOME=/opt/cargo RUSTUP_HOME=/opt/rustup PATH=/opt/cargo/bin:$PATH
RUN curl -fsSL https://sh.rustup.rs -o /tmp/rustup.sh \
    && sh /tmp/rustup.sh -y --profile minimal --default-toolchain 1.98.1 \
    && rm /tmp/rustup.sh
ENV APPIMAGE_EXTRACT_AND_RUN=1 CI=true
WORKDIR /work
