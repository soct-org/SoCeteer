#!/bin/sh
# /init of the shell boot image: BusyBox userspace with an interactive shell on the
# console. This script stays PID 1, which must never exit - the shell runs as a child
# and is respawned, so exit/Ctrl-D just yields a fresh one.
export PATH=/bin

/bin/busybox mount -t proc proc /proc
/bin/busybox mount -t sysfs sysfs /sys
/bin/busybox mount -t devtmpfs devtmpfs /dev
/bin/busybox --install -s /bin

# The out-of-tree drivers baked into this image - the SD controller first: it hands the
# boot medium to Linux as /dev/mmcblk0 (mount the FAT partition from the shell, e.g.
# `mount -t vfat /dev/mmcblk0p1 /mnt` - or /dev/mmcblk0 when the card has no table).
for m in /lib/modules/*.ko; do
    [ -e "$m" ] && insmod "$m"
done

echo
echo "=== SoCeteer Linux: $(uname -sr), $(busybox | busybox head -1 | busybox cut -d' ' -f1-2) ==="

# setsid + cttyhack give the shell a session and the real console tty (found via
# /sys/class/tty/console/active) as controlling terminal - i.e. working job control;
# -l makes it a login shell so /etc/profile runs (terminal-size setup, see profile.sh).
while :; do
    setsid cttyhack sh -l
done
