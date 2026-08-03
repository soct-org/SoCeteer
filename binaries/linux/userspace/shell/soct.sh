#!/bin/sh
# /bin/soct - the persistent environment: a real (ext2) filesystem living in a single
# file, .soct/rootfs.ext2, on a FAT medium (SD card or USB stick). FAT cannot host a
# root filesystem - no symlinks, no permissions - but the loop-mounted image can, so
# everything inside, /home/soct above all, survives reboots and travels with the
# medium between boards. The prompt inside is soct@<hostname>, resolved at login, so a
# traveling medium always names the board it woke up on.
#
# Mount map: the carrier medium mounts at /media/<device> (e.g. /media/mmcblk0p1) and
# the environment itself at /soct-fs - so the outer shell reads and writes the
# persistent tree directly (/soct-fs/home/soct) while `soct` chroots into it.
#
# Without arguments, every candidate device is probed and listed with what it holds;
# the environment is entered when exactly one exists, created (then entered) when
# exactly one FAT volume could hold a new one, and any ambiguity keeps the choice
# with the user: soct <device>. The candidate list mirrors the storage drivers this
# image carries - mmcblk* is the AXI SD controller (fpga-axi-sdc), sd* is USB mass
# storage behind the xHCI - and grows alongside them.
#
# Leaving is plain `exit`: when the last session leaves (both consoles can be
# inside), everything is unmounted and the medium can be pulled. The environment
# image is journal-less ext2 - removing the medium while it is mounted can corrupt
# it, so teardown is automatic rather than a separate command one can forget.
SOCT_FS=/soct-fs
MEDIA=/media
SIZE_MB=${SOCT_PERSIST_MB:-64}
PROBE=/tmp/.soct-probe
IMG_REL=.soct/rootfs.ext2

candidates() {
    for _dev in /dev/mmcblk0p1 /dev/mmcblk0p2 /dev/mmcblk0 \
                /dev/sda1 /dev/sdb1 /dev/sda /dev/sdb; do
        [ -b "$_dev" ] && echo "$_dev"
    done
}

# What a device holds: "env" (a soct environment), "fat" (a FAT without one), "none".
probe_dev() {
    mkdir -p "$PROBE"
    if mount -t vfat -o ro "$1" "$PROBE" 2>/dev/null; then
        if [ -f "$PROBE/$IMG_REL" ]; then echo env; else echo fat; fi
        umount "$PROBE"
    else
        echo none
    fi
}

# Teardown, run when the login shell returns: a chroot cannot unmount its own
# root, so freeing the environment belongs to the moment control is back on the
# ramdisk. Anything still rooted in the environment keeps it mounted, and is
# listed with its console - a session on the OTHER console (serial vs monitor)
# is otherwise invisible from this one. Only the last one out unmounts, and
# only then is the medium safe to remove.
leave_env() {
    grep -q " $SOCT_FS " /proc/mounts || return
    _inuse=""
    for _p in /proc/[0-9]*; do
        [ "$(readlink "$_p/root" 2>/dev/null)" = "$SOCT_FS" ] || continue
        if [ -z "$_inuse" ]; then
            _inuse=1
            echo "soct: environment left mounted, still in use by:"
        fi
        echo "  pid ${_p#/proc/}  $(cat "$_p/comm" 2>/dev/null)  on $(readlink "$_p/fd/0" 2>/dev/null || echo '?')"
    done
    [ -n "$_inuse" ] && return
    # The media rbind is a subtree (one mount per carrier), which only a lazy
    # umount takes down in one go.
    umount -l "$SOCT_FS/media" 2>/dev/null
    for _d in dev sys proc; do
        umount "$SOCT_FS/$_d" 2>/dev/null
    done
    if ! umount -d "$SOCT_FS" 2>/dev/null; then
        echo "soct: $SOCT_FS is busy (a shell or open file in it?) - left mounted" >&2
        return
    fi
    _carriers=""
    while read -r _dev _mnt _type _rest; do
        [ "$_type" = vfat ] || continue
        case "$_mnt" in
            "$MEDIA"/*) _carriers="$_carriers $_mnt" ;;
        esac
    done < /proc/mounts
    _ok=1
    for _m in $_carriers; do
        umount "$_m" || { echo "soct: $_m is busy - not unmounted" >&2; _ok=""; }
    done
    [ -n "$_ok" ] && echo "soct: unmounted - the medium can be removed"
}

# Bind the pseudo filesystems and the media tree into the environment, run the
# login shell inside it, and unmount when the last session has left. The cd:
# this script outlives the shell, and its own working directory must not be
# what keeps the environment busy during teardown.
enter_env() {
    for _d in proc sys dev; do
        grep -q " $SOCT_FS/$_d " /proc/mounts || mount -o bind "/$_d" "$SOCT_FS/$_d"
    done
    mkdir -p "$SOCT_FS/media"
    # rbind, not bind: the carrier media are MOUNTS under /media, and a plain bind
    # carries only the directory - the mountpoints would appear empty inside.
    grep -q " $SOCT_FS/media " /proc/mounts || mount -o rbind "$MEDIA" "$SOCT_FS/media"
    cd /
    env HOME=/home/soct TERM="${TERM:-linux}" chroot "$SOCT_FS" /bin/sh -l
    _rc=$?
    leave_env
    exit "$_rc"
}

# Mounts the carrier at /media/<device>, creates the image there when absent,
# loop-mounts the environment at $SOCT_FS, populates a fresh one, and enters.
enter_or_create() {
    _dev="$1"
    _mnt="$MEDIA/$(basename "$_dev")"

    if ! grep -q " $_mnt " /proc/mounts; then
        mkdir -p "$_mnt"
        if ! mount -t vfat "$_dev" "$_mnt"; then
            echo "soct: mounting $_dev at $_mnt failed" >&2
            exit 1
        fi
    fi
    echo "soct: medium $_dev at $_mnt"

    _img="$_mnt/$IMG_REL"
    _fresh=""
    if [ ! -f "$_img" ]; then
        echo "soct: creating a ${SIZE_MB} MB environment on $_dev"
        mkdir -p "$_mnt/.soct"
        if ! dd if=/dev/zero of="$_img" bs=1M count="$SIZE_MB" 2>/dev/null; then
            echo "soct: creating the image failed (medium full or read-only?)" >&2
            rm -f "$_img"
            exit 1
        fi
        if ! mke2fs -F "$_img" >/dev/null 2>&1; then
            echo "soct: formatting the image failed" >&2
            rm -f "$_img"
            exit 1
        fi
        _fresh=1
    fi

    mkdir -p "$SOCT_FS"
    if ! grep -q " $SOCT_FS " /proc/mounts; then
        if ! mount -o loop -t ext2 "$_img" "$SOCT_FS"; then
            echo "soct: mounting $_img failed (corrupt? move it away and re-run soct)" >&2
            exit 1
        fi
    fi
    echo "soct: environment at $SOCT_FS (also reachable from this shell)"

    # The environment carries copies of the tools from whatever image populated
    # it; a differing build identity means those copies are stale.
    if [ -f "$SOCT_FS/etc/soct-release" ] && ! cmp -s /etc/soct-release "$SOCT_FS/etc/soct-release"; then
        echo "soct: NOTE - this environment was created from a different image build:"
        echo "soct:   image:       $(grep SOCT_BUILD /etc/soct-release)"
        echo "soct:   environment: $(grep SOCT_BUILD "$SOCT_FS/etc/soct-release" || echo 'SOCT_BUILD=unknown')"
        echo "soct:   refresh its tools:  cp /bin/fbmode /bin/fbimg /bin/doom $SOCT_FS/bin/ && cp /etc/soct-release $SOCT_FS/etc/"
        echo "soct:   or recreate it:     exit, then  rm $_mnt/$IMG_REL  and re-run soct"
    fi

    if [ -n "$_fresh" ] || [ ! -x "$SOCT_FS/bin/busybox" ]; then
        echo "soct: populating the environment"
        mkdir -p "$SOCT_FS/bin" "$SOCT_FS/etc" "$SOCT_FS/proc" "$SOCT_FS/sys" \
                 "$SOCT_FS/dev" "$SOCT_FS/tmp" "$SOCT_FS/media" "$SOCT_FS/home/soct"
        cp /bin/busybox "$SOCT_FS/bin/"
        for _t in fbmode fbimg doom; do
            [ -x "/bin/$_t" ] && cp "/bin/$_t" "$SOCT_FS/bin/"
        done
        [ -f /etc/soct-release ] && cp /etc/soct-release "$SOCT_FS/etc/"
        chroot "$SOCT_FS" /bin/busybox --install -s /bin
        # The tty keeps the console profile's settings across chroot; $(hostname)
        # resolves at LOGIN, when the medium knows which board it woke up on.
        cat > "$SOCT_FS/etc/profile" <<'PROFILE'
# Login profile of the persistent environment (created by soct).
export HOME=/home/soct
export HISTFILE=$HOME/.ash_history
export PS1="soct@$(hostname):\w # "
cd "$HOME"
PROFILE
    fi

    enter_env
}

if [ $# -gt 1 ]; then
    echo "usage: soct [device]" >&2
    exit 2
fi

# An already-mounted environment keeps serving; switching devices means leaving
# it first (exit in every session unmounts it).
if grep -q " $SOCT_FS " /proc/mounts; then
    if [ $# = 1 ]; then
        echo "soct: an environment is already mounted at $SOCT_FS - exit it everywhere first" >&2
        exit 1
    fi
    enter_env
fi

if [ $# = 1 ]; then
    [ -b "$1" ] || { echo "soct: $1 is not a block device" >&2; exit 1; }
    enter_or_create "$1"
fi

_envs=""
_fats=""
_any=""
echo "soct: storage devices:"
for _d in $(candidates); do
    _any=1
    case "$(probe_dev "$_d")" in
        env)  echo "  $_d  vfat  soct environment"; _envs="$_envs $_d" ;;
        fat)  echo "  $_d  vfat  empty";            _fats="$_fats $_d" ;;
        none) echo "  $_d  -     no FAT filesystem" ;;
    esac
done
[ -z "$_any" ] && echo "  (none - insert an SD card or USB stick)"

set -- $_envs
_n_env=$#
_first_env="$1"
set -- $_fats
_n_fat=$#
_first_fat="$1"

if [ "$_n_env" = 1 ]; then
    enter_or_create "$_first_env"
elif [ "$_n_env" -gt 1 ]; then
    echo "soct: several environments - pick one: soct <device>" >&2
    exit 1
elif [ "$_n_fat" = 1 ]; then
    enter_or_create "$_first_fat"
elif [ "$_n_fat" -gt 1 ]; then
    echo "soct: several possible homes - pick one: soct <device>" >&2
    exit 1
else
    exit 1
fi
