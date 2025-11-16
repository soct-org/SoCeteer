#!/bin/bash
set -euo pipefail

install_dependencies() {
    $INSTALL git help2man perl python3 make autoconf g++ flex bison ccache mold
    $INSTALL libgoogle-perftools-dev numactl perl-doc
    $INSTALL libfl2  # Ubuntu only (ignore if gives error)
    $INSTALL libfl-dev  # Ubuntu only (ignore if gives error)
    $INSTALL zlibc zlib1g zlib1g-dev  # Ubuntu only (ignore if gives error)
}

# Check for --install-deps option
if [[ " $* " == *" --install-deps "* ]]; then
    # Check if running in Docker
    if [[ " $* " == *" --no-sudo "* ]]; then
        INSTALL="apt-get install"
    else
        INSTALL="sudo apt-get install"
    fi

    # If quiet, add to INSTALL command
    if [[ " $* " == *" --quiet "* ]]; then
        INSTALL="$INSTALL -y -f"
    fi
    install_dependencies
fi

# Build Verilator
VERILATOR_DIR=$1
if [ -z "$VERILATOR_DIR" ]; then
    echo "Usage: $0 <verilator_directory> [--install-deps] [--quiet] [--no-sudo]"
    exit 1
fi

START_DIR=$(pwd) || { echo "Failed to get current directory"; exit 1; }
unset VERILATOR_ROOT # Unset to avoid conflicts
mkdir -p "$VERILATOR_DIR" || { echo "Failed to create directory $VERILATOR_DIR"; exit 1; }
cd "$VERILATOR_DIR" || { echo "Failed to change directory to $VERILATOR_DIR"; exit 1; }
export PATH=/usr/bin:$PATH
autoconf || { echo "Autoconf failed"; exit 1; }
./configure || { echo "Configure failed"; exit 1; }

# Use nproc on Linux, sysctl on macOS, fallback to 2 if both fail
if command -v nproc >/dev/null 2>&1; then
    JOBS=$(nproc)
elif [[ "$OSTYPE" == "darwin"* ]]; then
    JOBS=$(sysctl -n hw.ncpu)
else
    JOBS=2
fi

make -j "$JOBS" || { echo "Make failed"; exit 1; }
cd "$START_DIR" || { echo "Failed to change back to start directory"; exit 1; }