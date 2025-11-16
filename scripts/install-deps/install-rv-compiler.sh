#!/bin/bash

# Which version of the RISC-V compiler to install - DO NOT CHANGE
DEFAULT_RISCV_VERSION="14.2.0-3"

# Check if argument is provided. If so, use it as the install path. Else, use ../../buildtools/vendor
if [ -z "$1" ]; then
    INSTALL_PATH="$(realpath ../../buildtools/vendor)"
else
    INSTALL_PATH="$1"
fi

echo "Installing RISC-V compiler to $INSTALL_PATH"

# Check if second argument is provided. If so, use it as the RISC-V version. Else, use the default version
if [ -z "$2" ]; then
    RISCV_VERSION="$DEFAULT_RISCV_VERSION"
else
    RISCV_VERSION="$2"
fi

echo "Using RISC-V compiler version $RISCV_VERSION"

get_platform_string() {
    local os
    local arch
    local platform_str

    os="$(uname -s | tr '[:upper:]' '[:lower:]')"
    arch="$(uname -m)"
    platform_str=""

    case "$os" in
        darwin)
            if [ "$arch" == "arm64" ]; then
                platform_str="darwin-arm64"
            else
                platform_str="darwin-x64"
            fi
            ;;
        linux)
            case "$arch" in
                arm*) platform_str="linux-arm" ;;
                aarch64) platform_str="linux-arm64" ;;
                x86_64) platform_str="linux-x64" ;;
                *) echo "Unsupported platform"; exit 1 ;;
            esac
            ;;
        *)
            echo "Unsupported platform"
            exit 1
            ;;
    esac

    echo "$platform_str"
}

PLATFORM_STR="$(get_platform_string)"

# Download the RISC-V compiler
echo "Downloading RISC-V compiler..."
wget "https://github.com/xpack-dev-tools/riscv-none-elf-gcc-xpack/releases/download/v$RISCV_VERSION/xpack-riscv-none-elf-gcc-$RISCV_VERSION-$PLATFORM_STR.tar.gz" -O /tmp/riscv-none-elf-gcc.tar.gz || { echo "Failed to download RISC-V compiler"; exit 1; }
echo "Creating directory $INSTALL_PATH"
mkdir -p "$INSTALL_PATH" || { echo "Failed to create directory $INSTALL_PATH"; exit 1; }
echo "Downloaded RISC-V compiler. Extracting..."
tar -xzf /tmp/riscv-none-elf-gcc.tar.gz -C "$INSTALL_PATH" || { echo "Failed to extract RISC-V compiler"; exit 1; }
# Rename to riscv-none-elf-gcc
mv "$INSTALL_PATH/xpack-riscv-none-elf-gcc-$RISCV_VERSION" "$INSTALL_PATH/riscv-none-elf-gcc" || { echo "Failed to rename RISC-V compiler"; exit 1; }
echo "RISC-V compiler installed to $INSTALL_PATH/riscv-none-elf-gcc"
rm /tmp/riscv-none-elf-gcc.tar.gz || { echo "Failed to clean up"; exit 1; }
