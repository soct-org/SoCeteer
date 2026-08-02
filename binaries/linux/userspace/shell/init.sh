#!/bin/sh
# /init of the shell boot image: BusyBox userspace with an interactive shell on the
# console. This script stays PID 1, which must never exit - the shell runs as a child
# and is respawned, so exit/Ctrl-D just yields a fresh one.
export PATH=/bin

/bin/busybox mount -t proc proc /proc
/bin/busybox mount -t sysfs sysfs /sys
/bin/busybox mount -t devtmpfs devtmpfs /dev
/bin/busybox --install -s /bin

# Kernel messages race init's own output on the console (module probes and the display
# bring-up print for seconds); keep the console to the kernel's emergencies until the
# banner is out, so it renders as one block. Everything suppressed here is in `dmesg`.
_printk="$(cut -f1 /proc/sys/kernel/printk)"
dmesg -n 1

# The out-of-tree drivers baked into this image - the SD controller first: it hands the
# boot medium to Linux as /dev/mmcblk0 (mount the FAT partition from the shell, e.g.
# `mount -t vfat /dev/mmcblk0p1 /mnt` - or /dev/mmcblk0 when the card has no table).
for m in /lib/modules/*.ko; do
    [ -e "$m" ] && insmod "$m"
done

# The machine's name comes from the design (/chosen/soct,board in the DTS); prompts
# read soct@<board>. Designs without the property still get a name.
if [ -r /proc/device-tree/chosen/soct,board ]; then
    hostname "$(tr -d '\0' < /proc/device-tree/chosen/soct,board)"
else
    hostname soct
fi

# The tools packed beside busybox: every real executable in /bin (the rest are
# busybox applet links), so the listing keeps itself current.
_tools=""
for _f in /bin/*; do
    [ -f "$_f" ] && [ ! -L "$_f" ] && [ "$_f" != /bin/busybox ] && _tools="$_tools ${_f##*/}"
done
. /etc/soct-release 2>/dev/null

echo
echo "==============================================================================="
echo "  SoCeteer v${SOCT_VERSION:-?} on $(hostname)"
echo "  $(uname -sr)  -  $(busybox | busybox head -1 | busybox cut -d' ' -f1-2)"
echo "  Tools:$_tools"
echo "  This shell is a ramdisk - nothing persists. 'soct' lists the SD/USB"
echo "  devices and enters (or creates) the persistent environment there;"
echo "  leaving it (exit) unmounts, so the medium can be pulled."
echo "  Kernel messages: dmesg"
echo "==============================================================================="
dmesg -n "${_printk:-7}"

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
# The local console, when the kernel has one: on a display design the framebuffer console
# renders tty1 on the monitor and the USB keyboard types into it - a second, independent
# shell beside the serial one. Spawned whenever the VT exists (without a display it is
# merely invisible, not harmful).
if [ -c /dev/tty1 ]; then
    (while :; do setsid sh -l </dev/tty1 >/dev/tty1 2>&1; done) &
fi

while :; do
    setsid cttyhack sh -l &
    child=$!
    while kill -0 "$child" 2>/dev/null; do
        wait "$child"
    done
done
