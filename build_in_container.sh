#!/bin/sh
# Builds the unified payload inside the Linux image from build-env/Dockerfile.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ENVIRONMENT="$ROOT/build-env"
CACHE="$ROOT/Releases/cache/container"
DOCKER="${DOCKER:-docker}"
IMAGE="${VOYAHTUNE_BUILD_IMAGE:-voyahtune-payload-build}"
PLATFORM="${VOYAHTUNE_BUILD_PLATFORM:-linux/amd64}"
ANDROID_SDK=/opt/android-sdk

message() {
    printf '%s\n' "$*" >&2
}

usage() {
    cat <<'USAGE'
Использование: ./build_in_container.sh [--shell] [--rebuild-image] [аргументы make_release.sh]

Собирает образ из build-env/Dockerfile и выполняет сборку в контейнере под текущим UID.
Аргументы make_release.sh передаются без изменений, например:

  ./build_in_container.sh 4.6.2 --payload --minimum-installer-version 1.10.1 --minimum-ota-version 4.6.2
  ./build_in_container.sh 4.6.2 --payload --minimum-installer-version 1.10.1 --minimum-ota-version 4.6.2 --no-zip
  ./build_in_container.sh --shell

Опции:
  --shell           оболочка в подготовленном окружении вместо сборки
  --rebuild-image   пересобрать образ даже при совпадающем Dockerfile
  -h, --help        эта справка
USAGE
}

dockerfile_digest() {
    if command -v sha256sum > /dev/null 2>&1; then
        sha256sum "$ENVIRONMENT/Dockerfile"
    else
        shasum -a 256 "$ENVIRONMENT/Dockerfile"
    fi
}

# AGP stores the absolute ndk-build/cmake path of the environment that generated it in the
# native configure model (.cxx/<build>/<hash>), and the model fingerprint and <hash> folder
# omit the SDK location. The image SDK is /opt/android-sdk while the host SDK is wherever
# local.properties points, so a leftover model makes the other environment exec a toolchain
# path that does not exist there: "A problem occurred starting process .../ndk-build".
clean_native_configure_state() {
    find "$ROOT/Native" "$ROOT/RestoreMode" "$ROOT/RunYN" "$ROOT/Updater" \
        -type d \( -name .cxx -o -path '*/build/intermediates/cxx' \) -prune \
        -exec rm -rf {} + 2>/dev/null || true
}

# Every project local.properties is the bind-mount target of the container SDK path, and the
# container never reads the checkout copy. Gradle still reads it on the host, so an anchor
# created without sdk.dir leaves host builds failing with "SDK location not found".
prepare_local_properties_anchors() {
    host_sdk=
    for project in RestoreMode Native RunYN Updater; do
        [ -n "$host_sdk" ] ||
            host_sdk=$(sed -n 's/^sdk\.dir=//p' "$ROOT/$project/local.properties" 2>/dev/null | tail -n 1)
    done
    [ -n "$host_sdk" ] || host_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
    if [ -z "$host_sdk" ]; then
        message "Не найден SDK хоста: задайте ANDROID_HOME или sdk.dir в local.properties любого проекта."
        message "local.properties проектов служат целью монтирования контейнера и должны оставаться"
        message "пригодными для сборки вне контейнера."
        return 1
    fi

    for project in Native RestoreMode RunYN Updater; do
        properties="$ROOT/$project/local.properties"
        [ -e "$properties" ] || : > "$properties"
        if ! grep -q '^sdk\.dir=' "$properties"; then
            if [ -s "$properties" ] && [ -n "$(tail -c 1 "$properties")" ]; then
                printf '\n' >> "$properties"
            fi
            printf 'sdk.dir=%s\n' "$host_sdk" >> "$properties"
        fi
    done
}

# An unclean shutdown journals the length of a Gradle lock file and loses its data pages, so the
# lock state becomes all zeros. Gradle never recovers from such a state itself: every later build
# stops with "Unexpected lock protocol found in lock file. Expected 3, found 0". The unreadable
# states and the immutable workspaces whose metadata was never written are dropped instead, and
# Gradle regenerates both. Only the container Gradle home is scanned: no other process keeps
# locks there while the wrapper runs, unlike the .gradle directories of the projects.
clean_damaged_gradle_cache_state() {
    gradle_home="$1"
    [ -d "$gradle_home" ] || return 0

    # A live lock file is never empty and a valid one starts with its protocol byte, so a zero
    # leading byte identifies the lost state without touching Cargo.lock and friends.
    find "$gradle_home" -type f -name '*.lock' -size -64c |
        while IFS= read -r lock; do
            if [ "$(od -An -tu1 -N1 "$lock" | tr -d '[:space:]')" = 0 ]; then
                rm -f "$lock"
                message "Сброшено повреждённое состояние блокировки Gradle: $lock"
            fi
        done

    [ -d "$gradle_home/caches" ] || return 0
    find "$gradle_home/caches" -path '*/transforms/*/metadata.bin' -empty |
        while IFS= read -r metadata; do
            workspace=$(dirname "$metadata")
            rm -rf "$workspace"
            message "Сброшен незавершённый кэш артефакт-транформов: $workspace"
        done
}

mode=build
rebuild=false
while [ "$#" -gt 0 ]; do
    case "$1" in
        --shell) mode=shell; shift ;;
        --rebuild-image) rebuild=true; shift ;;
        --help | -h) usage; exit 0 ;;
        --) shift; break ;;
        *) break ;;
    esac
done

if [ "$mode" = build ] && [ "$#" -eq 0 ]; then
    message "Укажите версию и аргументы make_release.sh; справка: ./build_in_container.sh --help"
    exit 2
fi

if ! command -v "$DOCKER" > /dev/null 2>&1; then
    message "Не найден Docker CLI: $DOCKER"
    exit 1
fi

signing_key="$HOME/.android/debug.keystore"
if [ ! -f "$signing_key" ]; then
    message "Нет ключа подписи: $signing_key"
    message "APK Native/RestoreMode собираются с debug-подписью, поэтому действующий ключ"
    message "переносится с прежней сборочной машины. См. Docs/releasing.md."
    exit 1
fi

mkdir -p "$CACHE/gradle" "$CACHE/cargo-registry"
printf 'sdk.dir=%s\n' "$ANDROID_SDK" > "$CACHE/local.properties"
prepare_local_properties_anchors
clean_damaged_gradle_cache_state "$CACHE/gradle"

# release.py keeps this directory for the whole build; a killed build leaves it behind.
release_lock="$ROOT/Releases/build/.release.lock"
if [ "$mode" = build ] && [ -e "$release_lock" ]; then
    message "Найден $release_lock — остаток прерванной сборки."
    message "Проверьте, что сборка не выполняется, и удалите этот каталог."
    exit 1
fi

tag="$IMAGE:$(dockerfile_digest | cut -d' ' -f1 | cut -c1-12)"
if [ "$rebuild" = true ] || ! "$DOCKER" image inspect "$tag" > /dev/null 2>&1; then
    message "Собираю образ $tag из build-env/Dockerfile"
    "$DOCKER" build --pull --platform "$PLATFORM" --tag "$tag" \
        --file "$ENVIRONMENT/Dockerfile" "$ENVIRONMENT"
fi

# A bumped ndkVersion otherwise fails deep inside Gradle, when AGP cannot install a
# missing NDK into the read-only SDK of the image.
skip_ndk_check=false
for argument in "$@"; do
    if [ "$argument" = "--no-build" ]; then
        skip_ndk_check=true
    fi
done

if [ "$skip_ndk_check" = false ]; then
    required_ndk_versions=$(
        find "$ROOT/Native" "$ROOT/RestoreMode" "$ROOT/RunYN" "$ROOT/Updater" \
            \( -name build -o -name .gradle \) -prune -o \
            -type f \( -name '*.kts' -o -name '*.gradle' \) \
            -exec grep -hE "ndkVersion[[:space:]]*=" {} + |
            grep -oE "[0-9]+\.[0-9]+\.[0-9]+" | sort -u
    )
    image_ndk_versions=$("$DOCKER" run --rm --platform "$PLATFORM" "$tag" ls -1 /opt/android-sdk/ndk | tr '\n' ' ')
    for version in $required_ndk_versions; do
        case " $image_ndk_versions " in
            *" $version "*) ;;
            *)
                message "Образ $tag не содержит NDK $version (в образе: $image_ndk_versions)."
                message "Добавьте \"ndk;$version\" в build-env/Dockerfile и пересоберите образ."
                exit 1
                ;;
        esac
    done
fi

if [ "$mode" = shell ]; then
    set -- bash
else
    set -- ./make_release.sh "$@"
fi

# Empty word keeps the container non-interactive for CI pipes and log capture.
terminal=
if [ -t 0 ] && [ -t 1 ]; then
    terminal=-it
fi

# The checkout keeps its host path: Gradle, CMake and Rust caches store absolute paths,
# and a different mount point invalidates them or breaks incremental dexing. The native
# configure state is the one cache that also stores the SDK location of its environment,
# so it is dropped on both sides of the container run instead of being shared.
clean_native_configure_state
trap clean_native_configure_state EXIT
"$DOCKER" run --rm --init ${terminal} \
    --platform "$PLATFORM" \
    --user "$(id -u):$(id -g)" \
    --workdir "$ROOT" \
    --volume "$ROOT:$ROOT" \
    --volume "$CACHE:/cache" \
    --volume "$CACHE/cargo-registry:/opt/cargo/registry" \
    --volume "$HOME/.android:/home/builder/.android" \
    --volume "$CACHE/local.properties:$ROOT/Native/local.properties:ro" \
    --volume "$CACHE/local.properties:$ROOT/RestoreMode/local.properties:ro" \
    --volume "$CACHE/local.properties:$ROOT/RunYN/local.properties:ro" \
    --volume "$CACHE/local.properties:$ROOT/Updater/local.properties:ro" \
    --env GRADLE_USER_HOME=/cache/gradle \
    "$tag" "$@"
