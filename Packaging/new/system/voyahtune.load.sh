#!/system/bin/sh
set -eu
RUNTIME=/data/local/bin/voyahtune-new
mkdir -p /data/local/bin /data/local/tmp /data/local/open_voyah/new
chown 0:0 /data/local /data/local/bin /data/local/open_voyah /data/local/open_voyah/new
chmod 751 /data/local
chmod 755 /data/local/bin
chmod 711 /data/local/open_voyah /data/local/open_voyah/new
chown 2000:2000 /data/local/tmp
chmod 771 /data/local/tmp
[ ! -L "$RUNTIME" ] && [ ! -L /data/local/bin ] || exit 1
chcon u:object_r:system_file:s0 /data/local/bin "$RUNTIME"
for file in "$RUNTIME"/agents.dex "$RUNTIME"/libvoyahtune-agent.so "$RUNTIME"/libvoyahtune-agent-arm.so "$RUNTIME"/voyahtune_*.json; do
    [ -f "$file" ] || continue
    [ ! -L "$file" ] || exit 1
    chcon u:object_r:system_file:s0 "$file"
done
exec "$RUNTIME/voyahtune-loader" --runtime-dir "$RUNTIME"
