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

# reboot(1) without -f never calls the kernel: it signals PID 1 (busybox convention:
# TERM = reboot, USR1 = halt, USR2 = poweroff) and expects init to finish the job.
# reboot -f syncs and makes the real syscall, which lands in the SBI SRST reset; the
# FPGA has no software power control, so halt/poweroff can only say so.
trap 'umount -a -r 2>/dev/null; reboot -f' TERM
trap 'echo "init: no power control on this SoC - use reboot"' USR1 USR2

# setsid + cttyhack give the shell a session and the real console tty (found via
# /sys/class/tty/console/active) as controlling terminal - i.e. working job control;
# -l makes it a login shell so /etc/profile runs (terminal-size setup, see profile.sh).
# The shell runs in the background with PID 1 sitting in `wait`: traps on a foreground
# child are deferred until it exits (POSIX), while the wait builtin is interruptible.
# The inner loop re-waits when a trap (e.g. the USR message) interrupted `wait` while
# the shell is still alive - only a real exit respawns it.
while :; do
    setsid cttyhack sh -l &
    child=$!
    while kill -0 "$child" 2>/dev/null; do
        wait "$child"
    done
done
