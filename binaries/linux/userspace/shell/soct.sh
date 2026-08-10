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
# Two safety nets close the gaps around that contract: a session KILLED instead of
# exited leaves the environment orphaned, and the next ramdisk login reclaims it
# (profile.sh runs `soct --reap`); a medium PULLED while mounted leaves dead mounts
# behind, and the next `soct` detects and detaches them instead of trusting them.
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

# Whether a mounted filesystem still reaches its device: read real file DATA through
# it, after dropping the caches - a medium pulled while mounted leaves its mounts
# behind, and cached state would answer a plain probe as if the device were there.
# Two specifics matter. The drop is 4-then-3: the kernel bounds this sysctl to 1..4
# (a combined "silent 3" of 7 is rejected as a whole), where 4 turns off the console
# message for every LATER write and 3 does the actual page+dentry/inode drop. And the
# probe must be a file-data read with its status checked: directory listings cannot
# fail here - vfat skips unreadable directory blocks and busybox ls swallows readdir
# errors - so $1 is a FILE that must exist on a live mount (a missing file counts as
# dead). Only called on mounts NO session inhabits - the cost is a cold cache, never
# inflicted on someone working inside.
mount_alive() {
    # sync first: drop_caches evicts only CLEAN pages, and the loop mount keeps the
    # image's superblock page dirty - on a live medium the sync cleans it, on a dead
    # one the failed writeback clears the dirty state either way, so the read below
    # really reaches the device.
    sync 2>/dev/null
    echo 4 > /proc/sys/vm/drop_caches 2>/dev/null
    echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
    dd if="$1" of=/dev/null bs=4096 count=1 2>/dev/null
}

# Whether another soct process is running (the shebang interpreter owns comm, so the
# script shows up in cmdline; the caller itself is skipped by pid). An entering
# session holds the environment mounted for seconds before its first process is
# rooted inside (mount, populate, then chroot) - during that window it is an owner,
# not an orphan, and neither the reaper nor another entry's stale check may touch
# the mounts.
soct_running() {
    for _p in /proc/[0-9]*; do
        [ "$_p" = "/proc/$$" ] && continue
        # Exact interpreter+script shape, so an unrelated argv merely mentioning the
        # path (vi /bin/soct) does not count; other REAPERS do not count either -
        # they own nothing, and counting them would make simultaneous logins on both
        # consoles abort each other's reap.
        case "$(tr '\0' ' ' < "$_p/cmdline" 2>/dev/null)" in
            *"/bin/sh /bin/soct --reap"*) ;;
            *"/bin/sh /bin/soct"*) return 0 ;;
        esac
    done
    return 1
}

# What a device holds: "env" (a soct environment), "fat" (a FAT without one), "none".
probe_dev() {
    # A carrier that is already mounted cannot be mounted a second time with a
    # different read-only state - the kernel refuses that with EBUSY - and mounting
    # the FAT by hand to copy files onto it is the normal thing to do. Look through
    # the mountpoint it already has, instead of calling the medium unformatted.
    _at=""
    while read -r _d _m _t _rest; do
        if [ "$_d" = "$1" ] && [ "$_t" = vfat ]; then _at="$_m"; break; fi
    done < /proc/mounts
    # A mount can outlive its medium (pulled while mounted): the device node is the
    # NEW card, the mount still the old one's dead superblock. When the mount shows an
    # environment, its image file is the file a live mount must be able to read - a dead
    # chain is detached (stderr: stdout is the verdict) and the actual medium probed
    # fresh below. A FAT without an environment offers no such file, and a directory
    # listing proves nothing (see mount_alive), so it is taken at face value; a stale
    # one surfaces loudly when creating the environment on it fails.
    if [ -n "$_at" ]; then
        if [ -f "$_at/$IMG_REL" ]; then
            if mount_alive "$_at/$IMG_REL"; then
                echo env
                return
            fi
            echo "soct: dropping stale mount of $1 at $_at (medium was removed while mounted)" >&2
            umount -l "$_at" 2>/dev/null
        else
            echo fat
            return
        fi
    fi
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
        # Rooted inside (a chroot session), or merely standing inside from the ramdisk
        # (cwd under /soct-fs - the advertised outer-shell workflow): both keep the
        # root umount busy, and must be found BEFORE the binds are taken down.
        case "$(readlink "$_p/root" 2>/dev/null):$(readlink "$_p/cwd" 2>/dev/null)" in
            "$SOCT_FS":*|*":$SOCT_FS"|*":$SOCT_FS/"*) ;;
            *) continue ;;
        esac
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
    _devs=""
    for _m in $_carriers; do
        while read -r _d _mp _rest; do
            [ "$_mp" = "$_m" ] && _devs="$_devs $_d"
        done < /proc/mounts
        umount "$_m" || { echo "soct: $_m is busy - not unmounted" >&2; _ok=""; }
    done
    [ -n "$_ok" ] || return
    # Only this script's own mounts are gone. A carrier mounted somewhere else too
    # still owes that filesystem its writes, so the medium is not free yet - saying
    # otherwise is how a card gets pulled mid-write.
    _still=""
    for _d in $_devs; do
        while read -r _d2 _mp2 _rest2; do
            [ "$_d2" = "$_d" ] && _still="$_still $_mp2"
        done < /proc/mounts
    done
    if [ -n "$_still" ]; then
        echo "soct: left its own mounts, but the medium is still mounted at:$_still" >&2
        echo "soct: unmount that before removing it" >&2
    else
        echo "soct: unmounted - the medium can be removed"
    fi
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
    # The scans above are snapshots: a reaper that raced this entry may have torn the
    # environment down between them and here. Entering the bare mountpoint directory
    # would LOOK like a session while persisting nothing - refuse it by name instead.
    if ! grep -q " $SOCT_FS " /proc/mounts; then
        echo "soct: the environment vanished while entering (reaped concurrently?) - re-run soct" >&2
        exit 1
    fi
    env HOME=/home/soct TERM="${TERM:-linux}" chroot "$SOCT_FS" /bin/sh -l
    _rc=$?
    leave_env
    exit "$_rc"
}

# Failing after the carrier was mounted must not leave it mounted: this script is
# the only thing that would ever unmount it, and it is about to exit. Mounts the
# caller already had are left alone.
_bail() {
    [ -n "$_we_mounted" ] && umount "$_mnt" 2>/dev/null
    exit 1
}

# Mounts the carrier at /media/<device>, creates the image there when absent,
# loop-mounts the environment at $SOCT_FS, populates a fresh one, and enters.
enter_or_create() {
    _dev="$1"
    _mnt="$MEDIA/$(basename "$_dev")"

    # Same stale-mount hazard as in probe_dev, reachable directly via `soct <device>`.
    if grep -q " $_mnt " /proc/mounts && [ -f "$_mnt/$IMG_REL" ] && ! mount_alive "$_mnt/$IMG_REL"; then
        echo "soct: dropping stale mount at $_mnt (medium was removed while mounted)"
        umount -l "$_mnt" 2>/dev/null
    fi

    _we_mounted=""
    if ! grep -q " $_mnt " /proc/mounts; then
        mkdir -p "$_mnt"
        if ! mount -t vfat "$_dev" "$_mnt"; then
            echo "soct: mounting $_dev at $_mnt failed" >&2
            exit 1
        fi
        _we_mounted=1
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
            _bail
        fi
        if ! mke2fs -F "$_img" >/dev/null 2>&1; then
            echo "soct: formatting the image failed" >&2
            rm -f "$_img"
            _bail
        fi
        _fresh=1
    fi

    mkdir -p "$SOCT_FS"
    if ! grep -q " $SOCT_FS " /proc/mounts; then
        if ! mount -o loop -t ext2 "$_img" "$SOCT_FS"; then
            echo "soct: mounting $_img failed (corrupt? move it away and re-run soct)" >&2
            _bail
        fi
    fi
    echo "soct: environment at $SOCT_FS (also reachable from this shell)"

    # The environment carries copies of the tools from whatever image populated
    # it; a differing build identity means those copies are stale.
    if [ -f "$SOCT_FS/etc/soct-release" ] && ! cmp -s /etc/soct-release "$SOCT_FS/etc/soct-release"; then
        echo "soct: NOTE - this environment was created from a different image build:"
        echo "soct:   image:       $(grep SOCT_BUILD /etc/soct-release)"
        echo "soct:   environment: $(grep SOCT_BUILD "$SOCT_FS/etc/soct-release" || echo 'SOCT_BUILD=unknown')"
        echo "soct:   refresh its tools:  cp /bin/fbmode /bin/fbimg $SOCT_FS/bin/ && cp /etc/soct-release $SOCT_FS/etc/"
        echo "soct:   or recreate it:     exit, then  rm $_mnt/$IMG_REL  and re-run soct"
    fi

    if [ -n "$_fresh" ] || [ ! -x "$SOCT_FS/bin/busybox" ]; then
        echo "soct: populating the environment"
        mkdir -p "$SOCT_FS/bin" "$SOCT_FS/etc" "$SOCT_FS/proc" "$SOCT_FS/sys" \
                 "$SOCT_FS/dev" "$SOCT_FS/tmp" "$SOCT_FS/media" "$SOCT_FS/home/soct"
        cp /bin/busybox "$SOCT_FS/bin/"
        for _t in fbmode fbimg; do
            [ -x "/bin/$_t" ] && cp "/bin/$_t" "$SOCT_FS/bin/"
        done
        [ -f /etc/soct-release ] && cp /etc/soct-release "$SOCT_FS/etc/"
        # busybox finds its own path through /proc/self/exe while it writes the applet
        # links. With no /proc in the fresh environment every link points at that literal
        # path instead of at busybox, and then resolves only while a busybox process
        # happens to be the one looking it up - the environment would not work when
        # chrooted into by hand, or read on another machine. init.sh mounts /proc before
        # its own --install for the same reason.
        mount -t proc proc "$SOCT_FS/proc" ||
            echo "soct: no /proc for the applet install - the links will be indirect" >&2
        chroot "$SOCT_FS" /bin/busybox --install -s /bin
        umount "$SOCT_FS/proc" 2>/dev/null
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

# Reaper hook, run by the ramdisk profile at every login: a session that was killed
# (instead of exiting) leaves the environment mounted with nobody left to free it.
# Reclaims it exactly when it is orphaned; silent in every other state, since a
# session on the other console legitimately keeps it mounted.
if [ "$1" = "--reap" ]; then
    grep -q " $SOCT_FS " /proc/mounts || exit 0
    soct_running && exit 0
    for _p in /proc/[0-9]*; do
        [ "$(readlink "$_p/root" 2>/dev/null)" = "$SOCT_FS" ] && exit 0
    done
    echo "soct: environment mounted with no session inside - unmounting"
    leave_env
    exit 0
fi

# An already-mounted environment keeps serving; switching devices means leaving
# it first (exit in every session unmounts it). Unless its medium is gone: then
# the whole chain (loop mount, binds, carrier) is dead weight - detach it and
# continue to a fresh probe instead of walking a corpse. An INHABITED environment
# is never probed: a session working inside is the strongest liveness signal
# there is, and the probe's cache drop would land on that session's working set.
if grep -q " $SOCT_FS " /proc/mounts; then
    _inhabited=""
    for _p in /proc/[0-9]*; do
        [ "$(readlink "$_p/root" 2>/dev/null)" = "$SOCT_FS" ] && { _inhabited=1; break; }
    done
    if [ -z "$_inhabited" ] && ! soct_running && ! mount_alive "$SOCT_FS/bin/busybox"; then
        echo "soct: the mounted environment is stale (medium removed while mounted?) - detaching it"
        umount -l "$SOCT_FS/media" 2>/dev/null
        for _d in dev sys proc; do
            umount -l "$SOCT_FS/$_d" 2>/dev/null
        done
        umount -l -d "$SOCT_FS" 2>/dev/null
    elif [ $# = 1 ]; then
        echo "soct: an environment is already mounted at $SOCT_FS - exit it everywhere first" >&2
        exit 1
    else
        enter_env
    fi
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
    echo "soct: no medium with a FAT filesystem to put an environment on" >&2
    exit 1
fi
