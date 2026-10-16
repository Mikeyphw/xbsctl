#!/system/bin/sh
SKIPMOUNT=false
PROPFILE=false
POSTFSDATA=false
LATESTARTSERVICE=true

MODDIR="${MODPATH:-${0%/*}}"
PATHS_LIB="$MODDIR/bin/xbs-paths.sh"
[ -f "$PATHS_LIB" ] && . "$PATHS_LIB"

ui_print "- Preparing XBS directories..."
command -v xbs_mkdirs >/dev/null 2>&1 && xbs_mkdirs
command -v xbs_seed_external_config >/dev/null 2>&1 && xbs_seed_external_config >/dev/null 2>&1 || true
ui_print "✔ XBS config root ready at ${XBS_EXTERNAL_ROOT:-/sdcard/.config/xbs}"

ui_print "- Setting Go-native runtime permissions..."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/customize.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
[ -e "$MODPATH/system/bin/XBSctl" ] && set_perm "$MODPATH/system/bin/XBSctl" 0 0 0755
[ -e "$MODPATH/system/bin/XtremeBSd" ] && set_perm "$MODPATH/system/bin/XtremeBSd" 0 0 0755
[ -e "$MODPATH/system/bin/xbs" ] && set_perm "$MODPATH/system/bin/xbs" 0 0 0755
[ -e "$MODPATH/runtime/arm64-v8a/xbs" ] && set_perm "$MODPATH/runtime/arm64-v8a/xbs" 0 0 0755
[ -e "$MODPATH/runtime/arm64-v8a/XBSctl" ] && set_perm "$MODPATH/runtime/arm64-v8a/XBSctl" 0 0 0755
[ -e "$MODPATH/runtime/arm64-v8a/XtremeBSd" ] && set_perm "$MODPATH/runtime/arm64-v8a/XtremeBSd" 0 0 0755
ui_print "✔ Go-native XBS permissions applied"
