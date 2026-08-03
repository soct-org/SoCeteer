# /etc/soct-quirks.sh - the shell image's hardware knowledge, sourced by init.sh.
#
# Everything keys on the DEVICE TREE, not on board names: a driver loads only
# where its hardware exists, and a quirk is listed only where the affected
# hardware is present - one image serves every board and design variant, and a
# design without some subsystem never sees its drivers or its quirks. Add
# board-bound (rather than hardware-bound) entries with a case on $(hostname),
# which init.sh sets from the design's device tree.

# True when the running design's device tree contains the compatible string.
dt_has() {
    grep -qrs "$1" /proc/device-tree/
}

# The device-tree compatible that proves a module's hardware exists; empty means
# the module is generic and always loads.
module_gate() {
    case "$1" in
        fpga-axi-sdc) echo "riscv,axi-sd-card-1.0" ;;
        soct-dp) echo "xlnx,axi-vdma-1.00.a" ;;
        *) echo "" ;;
    esac
}

# Known quirks of the running hardware, one line each; the banner prints them.
known_quirks() {
    if dt_has "snps,dwc3"; then
        echo "USB: can come back unusable from a warm reboot (the PS is not in"
        echo "     the reset network); power cycle + psu_init recovers it"
    fi
}
