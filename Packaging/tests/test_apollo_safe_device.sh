#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
temp=$(mktemp -d)
trap 'rm -rf "$temp"' EXIT
mkdir -p "$temp/bin" "$temp/data/user/0/ru.big.town.restoremode/shared_prefs" \
    "$temp/data/user/0/ru.big.town.anative/shared_prefs" \
    "$temp/data/user_de/0/ru.big.town.anative/files"
cat > "$temp/bin/settings" <<'SCRIPT'
#!/bin/sh
[ "$1" != get ] || echo 0
SCRIPT
cat > "$temp/bin/am" <<'SCRIPT'
#!/bin/sh
exit 0
SCRIPT
cat > "$temp/bin/sync" <<'SCRIPT'
#!/bin/sh
exit 0
SCRIPT
chmod +x "$temp/bin/"*

restore="$temp/data/user/0/ru.big.town.restoremode/shared_prefs/DrivePreferences.xml"
native="$temp/data/user/0/ru.big.town.anative/shared_prefs/NativePrefs.xml"
printf '<map>\n<boolean name="apolloStockUiEnabled" value="true" />\n<boolean name="apolloTlcEnabled" value="true" />\n</map>\n' > "$restore"
cp "$restore" "$restore.bak"
printf '<map>\n<boolean name="cacheApolloStockUiEnabled" value="true" />\n<boolean name="cacheApolloTrafficLightsEnabled" value="true" />\n</map>\n' > "$native"
printf 'enabled\n' > "$temp/data/user_de/0/ru.big.town.anative/files/apollo_settings_runtime.v1"
sed "s#/data/#$temp/data/#g" \
    "$root/Packaging/installer/common/apollo-safe-device.sh" > "$temp/apollo-safe.sh"

PATH="$temp/bin:$PATH" sh "$temp/apollo-safe.sh" > "$temp/result"
grep -q 'migrated=1' "$temp/result"
for file in "$restore" "$restore.bak" "$native"; do
    ! grep -q 'StockUiEnabled" value="true"' "$file"
done
grep -q 'apolloTlcEnabled" value="true"' "$restore"
grep -q 'cacheApolloTrafficLightsEnabled" value="true"' "$native"
grep -q 'apolloStockUiEnabled" value="true"' \
    "$temp/data/local/voyahtune-apollo-backup/DrivePreferences.xml"
test ! -e "$temp/data/user_de/0/ru.big.town.anative/files/apollo_settings_runtime.v1"
PATH="$temp/bin:$PATH" sh "$temp/apollo-safe.sh" > "$temp/result"
grep -q 'migrated=0' "$temp/result"
echo 'PASS: retired Apollo UI is disabled without changing feature targets'
