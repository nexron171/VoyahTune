#!/bin/bash
# Rebuild selected desktop tools using macOS and the prepared Colima builders.
set -euo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

CHECK=0
PAYLOAD=''
OUTPUT=''
MAC=0
WINDOWS=0
LINUX_BUILD=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h)
      echo 'Usage: ./Installer/scripts/build-all-macos.sh --payload DIRECTORY [--output Releases/build/DIRECTORY] [--mac] [--windows] [--linux]'
      echo 'Standalone macOS Universal + Windows x64 + Linux x64, with embedded payload.'
      echo 'Use ./make_release.sh VERSION --installers for the complete release.'
      echo '--check: check the prepared build environment without building.'
      exit 0 ;;
    --check) CHECK=1; shift ;;
    --mac) MAC=1; shift ;;
    --windows) WINDOWS=1; shift ;;
    --linux) LINUX_BUILD=1; shift ;;
    --payload|--output)
      [[ $# -ge 2 ]] || { echo "Missing value for $1" >&2; exit 2; }
      if [[ $1 == --payload ]]; then PAYLOAD=$2; else OUTPUT=$2; fi
      shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
if [[ $MAC == 0 && $WINDOWS == 0 && $LINUX_BUILD == 0 ]]; then MAC=1; WINDOWS=1; LINUX_BUILD=1; fi
REMOTE=0
if [[ $WINDOWS == 1 || $LINUX_BUILD == 1 ]]; then REMOTE=1; fi
if [[ $CHECK != 1 ]]; then
  [[ -n "$PAYLOAD" ]] || { echo 'Use ./make_release.sh VERSION --installers, or pass --payload DIRECTORY.' >&2; exit 2; }
  PAYLOAD=$(python3 - "$PAYLOAD" <<'PYLOAD'
import json,sys
from pathlib import Path
p=Path(sys.argv[1]).resolve()
json.loads((p/'manifest.json').read_text())
print(p)
PYLOAD
)
  PACKAGE_VERSION=$(python3 - "$PAYLOAD/manifest.json" <<'PYLOAD'
import json,sys
print(json.load(open(sys.argv[1]))['releaseVersion'])
PYLOAD
)
  OUTPUT=${OUTPUT:-"$ROOT/Releases/build/installers-$PACKAGE_VERSION"}
  OUTPUT=$(python3 - "$OUTPUT" "$ROOT/Releases" <<'PYLOAD'
from pathlib import Path
import sys
p=Path(sys.argv[1]).resolve()
root=Path(sys.argv[2]).resolve()
assert p.is_relative_to(root) and len(p.relative_to(root).parts)>=2, 'Output must be a release subdirectory inside Releases/build or Releases/dist'
print(p)
PYLOAD
)
fi
[[ $(uname -s) == Darwin ]] || { echo 'This script requires macOS' >&2; exit 1; }
TOOLS=(node npm python3)
if [[ $REMOTE == 1 ]]; then TOOLS+=(docker colima rsync); fi
if [[ $MAC == 1 ]]; then TOOLS+=(xcrun lipo); fi
for tool in "${TOOLS[@]}"; do
  command -v "$tool" >/dev/null || { echo "Missing command: $tool" >&2; exit 1; }
done
python3 -c 'import sys; assert sys.version_info >= (3,12), "Python 3.12+ required"'
if [[ $MAC == 1 ]]; then xcrun --find clang >/dev/null; fi

export COLIMA_HOME="$ROOT/Releases/cache/colima"
DOCKER=(docker --host "unix://$COLIMA_HOME/v/docker.sock")
mkdir -p "$ROOT/Releases/build" "$ROOT/Releases/cache"
LOCK="$ROOT/Releases/build/.installer-all.lock"
mkdir "$LOCK" || { echo "Another build is running, or a stale lock exists: $LOCK" >&2; exit 1; }
LOGS=$(mktemp -d "$ROOT/Releases/cache/installer-all-XXXXXX")
STAGE=''
STARTED_VM=0
STARTED_CONTAINERS=()
COMPLETED=0
cleanup() {
  result=$?
  if [[ $result == 0 && $COMPLETED != 1 ]]; then result=1; fi
  trap - EXIT
  if [[ ${#STARTED_CONTAINERS[@]} -gt 0 ]]; then
    "${DOCKER[@]}" stop "${STARTED_CONTAINERS[@]}" >>"$LOGS/cleanup.log" 2>&1 || true
  fi
  if [[ $STARTED_VM == 1 ]]; then
    colima stop v >>"$LOGS/cleanup.log" 2>&1 || true
  fi
  [[ -z "$STAGE" ]] || rm -rf "$STAGE"
  rmdir "$LOCK"
  if [[ $result != 0 ]]; then echo "Build failed. Logs: $LOGS" >&2; fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
echo "Logs: $LOGS"
if [[ $REMOTE == 1 ]]; then
if ! "${DOCKER[@]}" info >/dev/null 2>&1; then
  echo 'Starting Colima profile v…'
  colima start v >"$LOGS/colima.log" 2>&1
  STARTED_VM=1
fi

PAIRS=()
if [[ $WINDOWS == 1 ]]; then PAIRS+=('vti-windows:windows'); fi
if [[ $LINUX_BUILD == 1 ]]; then PAIRS+=('vti-linux-amd64:linux-amd64'); fi
for pair in "${PAIRS[@]}"; do
  container=${pair%%:*}
  hostdir="$ROOT/Releases/build/hosts/${pair#*:}"
  if ! "${DOCKER[@]}" inspect "$container" >"$LOGS/$container.json" 2>/dev/null; then
    echo "Missing prepared container $container. See Packaging/installer-tools/README.md." >&2
    exit 1
  fi
  python3 - "$LOGS/$container.json" "$hostdir" <<'PY'
import json,sys
from pathlib import Path
c=json.load(open(sys.argv[1]))[0]
assert any(m['Destination']=='/work' and m['Type']=='bind' and m['RW']
           and Path(m['Source']).resolve()==Path(sys.argv[2]).resolve()
           for m in c['Mounts']), f"Wrong /work mount in {c['Name']}; refusing to build in another checkout"
PY
  if [[ $("${DOCKER[@]}" inspect -f '{{.State.Running}}' "$container") != true ]]; then
    "${DOCKER[@]}" start "$container" >/dev/null
    STARTED_CONTAINERS+=("$container")
  fi
done
if [[ $WINDOWS == 1 ]]; then "${DOCKER[@]}" exec vti-windows sh -c 'command -v cargo-xwin && command -v 7z && rustup target list --installed | grep -Fx x86_64-pc-windows-msvc' >"$LOGS/windows-environment.log"; fi
if [[ $LINUX_BUILD == 1 ]]; then "${DOCKER[@]}" exec vti-linux-amd64 sh -c 'test "$(uname -m)" = x86_64 && command -v node && command -v cargo' >"$LOGS/linux-environment.log"; fi
fi
if [[ -d "$ROOT/Releases/cache/cargo" && -z ${CARGO_HOME:-} ]]; then
  export CARGO_HOME="$ROOT/Releases/cache/cargo"
  export RUSTUP_HOME="$ROOT/Releases/cache/rustup"
  export PATH="$CARGO_HOME/bin:$PATH"
fi
TOOLCHAIN=$(python3 -c 'import tomllib; print(tomllib.load(open("Installer/rust-toolchain.toml","rb"))["toolchain"]["channel"])')
if [[ $MAC == 1 ]]; then
rustup target list --toolchain "$TOOLCHAIN" --installed >"$LOGS/macos-targets.log"
grep -Fx aarch64-apple-darwin "$LOGS/macos-targets.log" >/dev/null
grep -Fx x86_64-apple-darwin "$LOGS/macos-targets.log" >/dev/null
fi
PINNED_DEPLOYER="$ROOT/Releases/cache/linuxdeploy-x86_64-new.AppImage"
if [[ $LINUX_BUILD == 1 && ( $(uname -m) == arm64 || -f "$PINNED_DEPLOYER" ) ]]; then
  python3 - "$PINNED_DEPLOYER" <<'PY'
import hashlib,sys
from pathlib import Path
p=Path(sys.argv[1])
assert p.is_file(), f"Missing verified Rosetta deployer: {p}. See installer-tools/README.md."
assert hashlib.sha256(p.read_bytes()).hexdigest()=='36a2d7e274d12e1050d0e9ecfe11d339ed54720b2bec464c286d53f8b07f5c62', f"Wrong deployer checksum: {p}"
PY
fi
if [[ $CHECK == 1 ]]; then COMPLETED=1; echo 'Environment checks passed. No tools rebuilt.'; exit 0; fi

VERSION=$(python3 -c 'import tomllib; print(tomllib.load(open("Installer/Cargo.toml","rb"))["workspace"]["package"]["version"])')
STAGE=$(mktemp -d "$ROOT/Releases/build/.installer-tools-XXXXXX")

# Snapshot both container sources before any host build mutates generated resources.
if [[ $REMOTE == 1 ]]; then
for pair in "${PAIRS[@]}"; do
  host=${pair#*:}
  rsync -a --delete --exclude target --exclude node_modules --exclude resources \
    --exclude binaries --exclude dist --exclude gen \
    Installer/ "Releases/build/hosts/$host/Installer/"
  mkdir -p "Releases/build/hosts/$host/Releases/build/embedded-payload"
  rsync -a --delete "$PAYLOAD/" "Releases/build/hosts/$host/Releases/build/embedded-payload/"
done
fi
if [[ $LINUX_BUILD == 1 && -f "$PINNED_DEPLOYER" ]]; then
  mkdir -p Releases/build/hosts/linux-amd64/Releases/cache
  cp "$PINNED_DEPLOYER" Releases/build/hosts/linux-amd64/Releases/cache/linuxdeploy-pinned.AppImage
fi

if [[ $MAC == 1 ]]; then
echo "macOS Universal tooling ${VERSION}…"
CARGO_TARGET_DIR="$ROOT/Installer/target" node Installer/scripts/build.mjs --bundles app --payload "$PAYLOAD" >"$LOGS/macos.log" 2>&1
MAC_APP="$ROOT/Installer/target/universal-apple-darwin/release/bundle/macos/VoyahTune Installer.app"
COPYFILE_DISABLE=1 tar -czf "$STAGE/macos-universal.tar.gz" -C "$(dirname "$MAC_APP")" "$(basename "$MAC_APP")"

fi
if [[ $WINDOWS == 1 ]]; then
echo 'Windows x64 tooling…'
"${DOCKER[@]}" exec -w /work vti-windows node Installer/scripts/build.mjs --target x86_64-pc-windows-msvc --payload /work/Releases/build/embedded-payload >"$LOGS/windows.log" 2>&1
WIN_TARGET="$ROOT/Releases/build/hosts/windows/Installer/target/x86_64-pc-windows-msvc/release"
cp "$WIN_TARGET/bundle/nsis/VoyahTune Installer_${PACKAGE_VERSION}_x64-setup.exe" "$STAGE/windows-x64.exe"
"${DOCKER[@]}" exec vti-windows 7z t "/work/Installer/target/x86_64-pc-windows-msvc/release/bundle/nsis/VoyahTune Installer_${PACKAGE_VERSION}_x64-setup.exe" >"$LOGS/windows-verify.log" 2>&1

fi
if [[ $LINUX_BUILD == 1 ]]; then
echo 'Linux x64 tooling…'
"${DOCKER[@]}" exec -w /work vti-linux-amd64 node Installer/scripts/build.mjs --no-bundle --payload /work/Releases/build/embedded-payload >"$LOGS/linux.log" 2>&1
"${DOCKER[@]}" exec -i -w /work vti-linux-amd64 bash -s >"$LOGS/linux-package.log" 2>&1 <<'LINUX'
set -euo pipefail
mkdir -p /opt/target/release
cp Installer/target/release/voyahtune-desktop Installer/target/release/installer-cli /opt/target/release/
rm -rf '/opt/target/release/bundle/appimage/VoyahTune Installer.AppDir' /opt/target/release/bundle/appimage_deb
cd Installer/desktop
if ! CARGO_TARGET_DIR=/opt/target npm run tauri -- bundle --bundles appimage --config src-tauri/tauri.release.conf.json >/tmp/vti-all-bundle.log 2>&1; then
  cat /tmp/vti-all-bundle.log
  grep -q 'failed to run linuxdeploy' /tmp/vti-all-bundle.log
  # Known Rosetta/static PIE output-plugin failure. Only use the verified deployer.
  echo '36a2d7e274d12e1050d0e9ecfe11d339ed54720b2bec464c286d53f8b07f5c62  /work/Releases/cache/linuxdeploy-pinned.AppImage' | sha256sum -c -
  # Its AppImage runtime is also static PIE. Extract the pinned SquashFS directly;
  # offset 944632 belongs to the SHA-256 checked immediately above.
  rm -rf /opt/vti-linuxdeploy-pinned
  unsquashfs -o 944632 -d /opt/vti-linuxdeploy-pinned /work/Releases/cache/linuxdeploy-pinned.AppImage
  cat >/root/.cache/tauri/linuxdeploy-x86_64.AppImage <<'DEPLOYER'
#!/bin/sh
if [ "${1:-}" = --appimage-extract-and-run ]; then shift; fi
export PATH="/root/.cache/tauri:$PATH"
exec /opt/vti-linuxdeploy-pinned/AppRun "$@"
DEPLOYER
  chmod +x /root/.cache/tauri/linuxdeploy-x86_64.AppImage
  mv /root/.cache/tauri/linuxdeploy-plugin-appimage.AppImage /opt/linuxdeploy-plugin-appimage.saved
  /root/.cache/tauri/linuxdeploy-x86_64.AppImage --appimage-extract-and-run \
    --appdir '/opt/target/release/bundle/appimage/VoyahTune Installer.AppDir' --plugin gtk --plugin gstreamer
else
  cat /tmp/vti-all-bundle.log
fi
cd /work
python3 Installer/scripts/package-linux.py \
  --appdir '/opt/target/release/bundle/appimage/VoyahTune Installer.AppDir' \
  --output /work/Releases/dist/linux-all-x64.run
/work/Releases/dist/linux-all-x64.run --cli verify
LINUX
cp Releases/build/hosts/linux-amd64/Releases/dist/linux-all-x64.run "$STAGE/linux-x64.run"
chmod +x "$STAGE/linux-x64.run"
fi
python3 - "$STAGE" "$OUTPUT" "$PAYLOAD" "$VERSION" <<'PY'
import hashlib,json,sys
from pathlib import Path
stage,dest,payload=map(Path,sys.argv[1:4])
manifest=json.loads((payload/'manifest.json').read_text())
def sha(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''): h.update(block)
    return h.hexdigest()
platforms={name:{'file':file,'sha256':sha(stage/file),'bytes':(stage/file).stat().st_size}
    for name,file in [('macos','macos-universal.tar.gz'),('windows','windows-x64.exe'),('linux','linux-x64.run')] if (stage/file).is_file()}
info={'releaseVersion':manifest['releaseVersion'],'buildRevision':manifest['buildRevision'],
      'engineVersion':sys.argv[4],'payloadSha256':sha(payload/'manifest.json'),'embeddedPayload':True,'platforms':platforms}
(stage/'build-info.json').write_text(json.dumps(info,ensure_ascii=False,indent=2)+'\n')
sys.path.insert(0, str(Path('Installer/scripts').resolve()))
from release import publish_outputs
publish_outputs([(stage,dest)])
print(f'Ready: {dest}')
PY
STAGE=''
COMPLETED=1
