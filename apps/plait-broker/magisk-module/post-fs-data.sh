#!/system/bin/sh
# Magisk post-fs-data (root, before zygote): stands in for PlaitOS's init (vendor/plait/hosts/plait_hosts.rc).
# Bind-mounts the DNS blocklist /data/plait/hosts over /system/etc/hosts. The system partition is untouched.
#
# No sepolicy.rule on purpose: Magisk loads those before Android starts, and a rule the kernel rejects then stops the
# phone from booting. The one rule needed is applied live instead; if it is refused, the overlay is skipped and the
# phone boots as usual. The file reuses an existing type (shell_data_file, which the broker's shell user may write);
# PlaitOS gives it its own type (plait_hosts_file) in its compiled policy.
D=/data/plait
magiskpolicy --live "allow netd shell_data_file file { open read getattr map }" || exit 0

mkdir -p $D || exit 0
[ -f $D/hosts.stock ] || cp /system/etc/hosts $D/hosts.stock || exit 0
[ -f $D/hosts ] || cp $D/hosts.stock $D/hosts || exit 0
chown 0:2000 $D && chmod 0775 $D
chmod 0644 $D/hosts.stock
chown 2000:2000 $D/hosts && chmod 0644 $D/hosts
chcon -R u:object_r:shell_data_file:s0 $D || exit 0

mount -o bind $D/hosts /system/etc/hosts
exit 0
