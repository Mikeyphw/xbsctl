#!/system/bin/sh
SKIPMOUNT=false
PROPFILE=false
POSTFSDATA=false
LATESTARTSERVICE=true

MODDIR="${MODPATH:-${0%/*}}"

# The former bin/xbs-paths.sh helper is not present in the standalone module.
# Initialization is handled by the bundled Go xbs CLI in service.sh after
# shared storage is mounted, not silently skipped during installation.
ui_print "- Go-native XBS config initialization is scheduled for first boot"

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
