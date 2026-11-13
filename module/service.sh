#!/system/bin/sh
MODDIR=${0%/*}
# Late start after Android's shared storage is available.
until [ "$(getprop sys.boot_completed)" = "1" ] && [ -d "/sdcard/Android" ]; do
  sleep 3
done
# xbs init only creates defaults when config is absent; it must never remove
# pre-existing user profiles/lists. Reject invalid config before any daemon.
if ! XBS_MODULE_DIR="$MODDIR" "$MODDIR/system/bin/xbs" init; then
  echo 'XBS R04: first-boot initialization failed' >&2
  exit 1
fi
if ! XBS_MODULE_DIR="$MODDIR" "$MODDIR/system/bin/xbs" validate; then
  echo 'XBS R04: configuration validation refused daemon startup' >&2
  exit 1
fi
XBS_MODULE_DIR="$MODDIR" "$MODDIR/system/bin/XtremeBSd" &
