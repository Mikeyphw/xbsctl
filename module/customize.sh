#!/system/bin/sh
SKIPMOUNT=false
PROPFILE=false
POSTFSDATA=false
LATESTARTSERVICE=true

# Refuse an unverified root-module payload before chmod or initialization.
[ -n "${MODPATH:-}" ] || { ui_print '! Missing MODPATH'; abort 'Missing module staging path'; }
[ -f "$MODPATH/runtime/arm64-v8a/runtime.sha256" ] || abort 'R04 checksum manifest absent'
if command -v sha256sum >/dev/null 2>&1; then
  SUM=sha256sum
elif command -v busybox >/dev/null 2>&1; then
  SUM='busybox sha256sum'
else
  abort 'R04 cannot verify runtime hashes: sha256sum unavailable'
fi
( cd "$MODPATH" && $SUM -c runtime/arm64-v8a/runtime.sha256 ) || abort 'R04 runtime checksum mismatch'
for x in xbs XBSctl XtremeBSd; do
  [ -f "$MODPATH/runtime/arm64-v8a/$x" ] || abort 'R04 missing Go runtime'
done
ui_print '- R04 runtime integrity verified (3 ARM64 binaries)'

# The Go CLI performs non-destructive configuration initialization on first boot.
ui_print '- Existing shared user configuration will be preserved'
set_perm_recursive "$MODPATH" 0 0 0755 0644
for rel in action.sh customize.sh service.sh system/bin/xbs system/bin/XBSctl system/bin/XtremeBSd runtime/arm64-v8a/xbs runtime/arm64-v8a/XBSctl runtime/arm64-v8a/XtremeBSd; do
  [ -f "$MODPATH/$rel" ] && set_perm "$MODPATH/$rel" 0 0 0755
done
ui_print '- R04 verified module permissions applied'
