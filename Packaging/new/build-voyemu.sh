#!/bin/sh
set -eu
SOLUTION=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY=$(CDPATH= cd -- "$SOLUTION/../.." && pwd)
if [ -z "${JAVA_HOME:-}" ] && [ "$(uname -s)" = Darwin ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 21)
    export JAVA_HOME
fi
VERSION=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["voyahtune"])' "$REPOSITORY/versions.json")
REVISION=$(git -C "$REPOSITORY" rev-parse --short=12 HEAD)
sh "$SOLUTION/build.sh"
python3 "$SOLUTION/scripts/prepare-voyemu.py"
for project in Native RestoreMode; do
    (
        cd "$REPOSITORY/$project"
        ./gradlew --offline --no-daemon --max-workers=2 :app:assembleRelease \
            "-PvoyahReleaseVersion=$VERSION" \
            "-PvoyahBuildRevision=$REVISION-unified-voyemu" \
            "-PvoyahInstallRecipe=$SOLUTION/build/voyemu/recipe.json" \
            "-PvoyahReleaseSources=$SOLUTION/build/voyemu/sources.json"
    )
done
python3 "$SOLUTION/scripts/prepare-voyemu.py" --finalize
