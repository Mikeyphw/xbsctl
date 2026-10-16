#!/system/bin/sh
# Do NOT assume where your module will be located.
# ALWAYS use $MODDIR if you need to know where this script
# and module is placed.
MODDIR=${0%/*}

# This script will be executed in late_start service mode.
until [ "$(getprop sys.boot_completed)" = "1" ] && [ -d "/sdcard/Android" ]; do
  sleep 3
done

# XGO10: the daemon is a Go-native runtime behind system/bin/XtremeBSd.
# Start the Go-native daemon directly during boot.
XBS_MODULE_DIR="$MODDIR" "$MODDIR/system/bin/XtremeBSd" &
