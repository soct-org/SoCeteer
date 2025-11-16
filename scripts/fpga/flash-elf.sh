#!/bin/bash

# This script is used to flash the ELF file to the FPGA using Vivado's xsdb

XSDB_SCRIPT="$1"
ELF_FILE="$2"

# These are passed as a0 and a1 in the sd bootrom. To stay compatible with it, we set them in the xsdb command as well.
# Default boot hart
BOOT_HART=0
# From common.h in the sd-card bootrom
BOOTROM_DTB_ADDR=0x00010080

# Check if the xsdb script and ELF file are provided
if [ -z "$ELF_FILE" ] || [ -z "$XSDB_SCRIPT" ]; then
    echo "Usage: $0 <path_to_xsdb_script> <path_to_elf_file>"
    echo "Example: $0 $HOME/tools/Xilinx/Vivado/2024.2/bin/xsdb <path_to_elf_file>"
    exit 1
fi
# Check if the xsdb script exists
if [ ! -f "$XSDB_SCRIPT" ]; then
    echo "Error: xsdb script not found at $XSDB_SCRIPT"
    exit 1
fi

# Check if the ELF file exists
if [ ! -f "$ELF_FILE" ]; then
    echo "Error: ELF file not found at $ELF_FILE"
    exit 1
fi

# Execute the xsdb script
${XSDB_SCRIPT} -eval "connect; targets -set -filter {name =~ \"Hart #0*\"}; stop; dow -clear ${ELF_FILE}; rwr a0 $BOOT_HART; rwr a1 $BOOTROM_DTB_ADDR; con"
