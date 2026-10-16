#!/system/bin/sh

echo "Please wait..."
MODDIR="${MODPATH:-${0%/*}}"
CTL="$MODDIR/system/bin/XBSctl"
if [ -x "$CTL" ]; then
  XBS_MODULE_DIR="$MODDIR" "$CTL" reload || true
  echo
  XBS_MODULE_DIR="$MODDIR" "$CTL" status || true
else
  echo "XBSctl is not installed."
fi
