#!/system/bin/sh
# Do NOT assume where your module will be located.
# ALWAYS use $MODDIR if you need to know where this script
# and module is placed.
MODDIR=${0%/*}

# This script will be executed in late_start service mode.
until [ "$(getprop sys.boot_completed)" = "1" ] && [ -d "/sdcard/Android" ]; do
  sleep 3
done

# Initialize once shared Android storage is available. Existing configurations are preserved.
# Refuse daemon startup if initialization or validation fails.
if ! XBS_MODULE_DIR="$MODDIR" "$MODDIR/system/bin/xbs" init; then
  echo "XBS: configuration initialization failed; not starting XtremeBSd" >&2
  exit 1
fi

# XGO10: daemon is a Go-native runtime behind system/bin/XtremeBSd.
XBS_MODULE_DIR="$MODDIR" "$MODDIR/system/bin/XtremeBSd" &
