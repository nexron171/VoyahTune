# Build-only Linux environment for the unified VoyahTune payload: Native/RestoreMode/RunYN/Updater
# APKs, the OTA root service, the Go loader and the checks from Packaging/new/check.sh.
#
# Versions mirror Packaging/new/native/dependencies.lock.json and the Gradle projects.
# Update the SDK packages here when compileSdk, ndkVersion or build tools change.
# The image copies no project files; the checkout arrives through the /work mount.
FROM ubuntu:24.04

SHELL ["/bin/bash", "-euo", "pipefail", "-c"]

ARG GO_VERSION=1.27.3
ARG GO_SHA256=65efbc80fdc1fa2cd2631cad5b481777a68e8725f34e89f50fc6ac714e379b40
ARG NODE_VERSION=22.23.4
ARG NODE_SHA256=1805bcd35e6815559bd2eb97db438d3fdb0eb3a5e6a977b7c1badca76337e9ce
ARG ANDROID_COMMAND_LINE_TOOLS_ZIP=commandlinetools-linux-16111833_latest.zip
ARG ANDROID_COMMAND_LINE_TOOLS_SHA1=e025545c62a8e64c7559119566a569fb1dec5f60
ARG RUST_TOOLCHAIN=1.98.1
ARG ANDROID_RUST_TARGETS="aarch64-linux-android x86_64-linux-android"
ARG GSON_VERSION=2.11.0
ARG GSON_SHA256=57928d6e5a6edeb2abd3770a8f95ba44dce45f3b23b7a9dc2b309c581552a78b

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    HOME=/home/builder \
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_NDK_HOME=/opt/android-sdk/ndk/27.0.12077973 \
    RUSTUP_HOME=/opt/rustup \
    CARGO_HOME=/opt/cargo \
    VOYAHTUNE_TEST_GSON_JAR=/opt/voyahtune-test/gson-2.11.0.jar \
    PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:/opt/cargo/bin:/opt/android-sdk/cmdline-tools/latest/bin:/usr/local/go/bin:/usr/local/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        build-essential ca-certificates curl file git openjdk-21-jdk-headless \
        patch python3 unzip xz-utils zip \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir --parents /home/builder/.android \
    && chmod 777 /home/builder /home/builder/.android

RUN curl -fsSL "https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz" --output /tmp/go.tar.gz \
    && echo "${GO_SHA256}  /tmp/go.tar.gz" | sha256sum --check --status \
    && tar --directory /usr/local --extract --gzip --file /tmp/go.tar.gz \
    && rm /tmp/go.tar.gz

RUN curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz" \
        --output /tmp/node.tar.xz \
    && echo "${NODE_SHA256}  /tmp/node.tar.xz" | sha256sum --check --status \
    && tar --directory /usr/local --extract --xz --file /tmp/node.tar.xz \
    && mv "/usr/local/node-v${NODE_VERSION}-linux-x64" /usr/local/node \
    && rm /tmp/node.tar.xz

# Cargo keeps registry downloads in CARGO_HOME/registry, which the wrapper mounts;
# CARGO_HOME itself stays writable for package cache locks.
RUN curl -fsSL https://sh.rustup.rs --output /tmp/rustup.sh \
    && sh /tmp/rustup.sh --yes --no-modify-path --profile minimal \
        --default-toolchain "${RUST_TOOLCHAIN}" --component rustfmt,clippy \
    && rm /tmp/rustup.sh \
    && for target in ${ANDROID_RUST_TARGETS}; do \
        rustup target add --toolchain "${RUST_TOOLCHAIN}" "${target}"; \
    done \
    && chmod --recursive a+rX "${RUSTUP_HOME}" \
    && chmod a+rw "${RUSTUP_HOME}/settings.toml" \
    && chmod a+rwX "${CARGO_HOME}"

RUN curl -fsSL "https://dl.google.com/android/repository/${ANDROID_COMMAND_LINE_TOOLS_ZIP}" \
        --output /tmp/command-line-tools.zip \
    && echo "${ANDROID_COMMAND_LINE_TOOLS_SHA1}  /tmp/command-line-tools.zip" | sha1sum --check --status \
    && mkdir --parents /tmp/command-line-tools "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && unzip -q /tmp/command-line-tools.zip -d /tmp/command-line-tools \
    && mv /tmp/command-line-tools/cmdline-tools "${ANDROID_SDK_ROOT}/cmdline-tools/latest" \
    && rm --recursive --force /tmp/command-line-tools /tmp/command-line-tools.zip

# Accepting licenses ends with SIGPIPE once sdkmanager stops reading, hence the explicit exit.
# Packages are extracted as mode 764 (execute only for the owner), while the build runs as
# the host UID; the tools become executable for everyone inside the same layer.
# NDK 27 builds the runtime and the OTA service; diplay-shared declares NDK 28.
RUN { yes | sdkmanager --sdk_root="${ANDROID_SDK_ROOT}" --licenses > /dev/null 2>&1 || true; } \
    && sdkmanager --sdk_root="${ANDROID_SDK_ROOT}" --install \
        "platforms;android-35" "platforms;android-36" "platforms;android-37.0" \
        "build-tools;35.0.0" "build-tools;36.0.0" "cmake;3.22.1" \
        "ndk;27.0.12077973" "ndk;28.2.13676358" \
    && chmod --recursive a+rX "${ANDROID_SDK_ROOT}"

# Runs the toolchain as an unprivileged user, the way the build container does.
RUN test -f "${ANDROID_SDK_ROOT}/platforms/android-35/android.jar" \
    && test -f "${ANDROID_SDK_ROOT}/platforms/android-36/android.jar" \
    && test -f "${ANDROID_SDK_ROOT}/platforms/android-37.0/android.jar" \
    && test -f "${ANDROID_SDK_ROOT}/ndk/28.2.13676358/ndk-build" \
    && test -x "${ANDROID_SDK_ROOT}/ndk/28.2.13676358/prebuilt/linux-x86_64/bin/make" \
    && unprivileged() { setpriv --reuid 65534 --regid 65534 --clear-groups "$@"; } \
    && unprivileged java -version \
    && unprivileged d8 --version \
    && unprivileged "${ANDROID_SDK_ROOT}/cmake/3.22.1/bin/cmake" --version \
    && unprivileged "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" --version \
    && unprivileged cargo --version \
    && unprivileged go version \
    && unprivileged node --version \
    && unprivileged python3 --version

RUN mkdir --parents /opt/voyahtune-test \
    && curl -fsSL "https://repo1.maven.org/maven2/com/google/code/gson/gson/${GSON_VERSION}/gson-${GSON_VERSION}.jar" \
        --output "${VOYAHTUNE_TEST_GSON_JAR}" \
    && echo "${GSON_SHA256}  ${VOYAHTUNE_TEST_GSON_JAR}" | sha256sum --check --status

WORKDIR /work
