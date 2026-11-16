ui_print "Plait Overlay Helper (TEST ONLY)"
ui_print "- Runs ONE root process that registers color-only overlays named plait* for Plait Broker"
ui_print "- PlaitOS does this without root (patched OverlayManagerService); uninstall this module there"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
