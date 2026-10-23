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
while [ -e /data/local/bin/voyahtune-update.block ]; do sleep 10; done
"$RUNTIME/voyahtune-loader" --runtime-dir "$RUNTIME" --check
exec "$RUNTIME/voyahtune-loader" --runtime-dir "$RUNTIME"
