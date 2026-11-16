ui_print "Plait Broker (PlaitOS test module)"
ui_print "- App installed as a system_ext priv-app, launcher in /system_ext/bin, like in the ROM"
ui_print "- Daemon runs as uid shell in u:r:shell:s0"
ui_print "- DNS blocklist: /data/plait/hosts is mounted over /system/etc/hosts at boot (restart to start it)"
ui_print "- Press Action in Magisk to see the daemon's status"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/system/system_ext/bin/plait-broker" 0 2000 0755
