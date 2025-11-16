@echo off

:: Print untested warning
echo Warning: This script has not been tested on Windows. Please report any issues on the GitHub repository.

:: Default version of the RISC-V compiler
set DEFAULT_RISCV_VERSION=14.2.0-3

:: Check if first argument is provided. If so, use it as the install path. Else, use ..\buildtools\vendor
if "%~1"=="" (
    set INSTALL_PATH=%CD%\..\buildtools\vendor
) else (
    set INSTALL_PATH=%~1
)

:: Check if second argument is provided. If so, use it as the RISC-V version. Else, use default version
if "%~2"=="" (
    set RISCV_VERSION=%DEFAULT_RISCV_VERSION%
) else (
    set RISCV_VERSION=%~2
)

:: Set platform string (only win32-x64 is supported)
set PLATFORM_STR=win32-x64

:: Download the RISC-V compiler
echo Downloading RISC-V compiler...
PowerShell -Command "Invoke-WebRequest -Uri 'https://github.com/xpack-dev-tools/riscv-none-elf-gcc-xpack/releases/download/v%RISCV_VERSION%/xpack-riscv-none-elf-gcc-%RISCV_VERSION%-%PLATFORM_STR%.zip' -OutFile '%TEMP%\riscv-none-elf-gcc.zip'" || (
    echo Failed to download RISC-V compiler
    exit /b 1
)

echo Downloaded RISC-V compiler. Extracting...
PowerShell -Command "Expand-Archive -Path '%TEMP%\riscv-none-elf-gcc.zip' -DestinationPath '%INSTALL_PATH%' -Force" || (
    echo Failed to extract RISC-V compiler
    exit /b 1
)

echo Extracted RISC-V compiler to %INSTALL_PATH%. Cleaning up...
del /q "%TEMP%\riscv-none-elf-gcc.zip" || (
    echo Failed to clean up
    exit /b 1
)

:: Rename to riscv-none-elf-gcc
ren "%INSTALL_PATH%\xpack-riscv-none-elf-gcc-%RISCV_VERSION%" "%INSTALL_PATH%\riscv-none-elf-gcc" || (
    echo Failed to rename RISC-V compiler
    exit /b 1
)

echo RISC-V compiler installation completed successfully.
