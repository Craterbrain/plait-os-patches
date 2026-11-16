ui_print "Plait Shield (PlaitOS test module)"
ui_print "- App installed as a system app in /product/app, like in the ROM"
ui_print "- No boot scripts and no SELinux rules: it only works through Plait Broker"
set_perm_recursive "$MODPATH" 0 0 0755 0644
