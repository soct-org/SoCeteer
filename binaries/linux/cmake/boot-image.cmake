# Kernel + OpenSBI machinery (shared by every boot image). The initramfs description is a
# FIXED path inside the kernel build dir; each program's boot image copies its own
# description there before building, so switching programs re-runs only the initramfs
# packing and the vmlinux link - never a reconfigure.
#
# Consumes: SOCT_BOOT_LINUX_DIR/SOCT_BOOT_OPENSBI_DIR, SOCT_DTS, SOCT_MEM_BASE_ADDR,
#           SOCT_ELFS_DIR, DTC, _llvm_bindir/_llvm_lld, _boot_make, _kenv/_khostflags,
#           _ncpu.
# Defines: the kernel-config and boot-dtb targets, the soct_kbuild job pool, the
#          _kbuild/_kdesc/_kllvm variables the module build reuses, and the
#          soct_linux_add_boot_image() function.

set(_kbuild "${CMAKE_CURRENT_BINARY_DIR}/kernel")
set(_kdesc "${_kbuild}/initramfs.desc")
set(KBUILD_DESC "${_kdesc}")
configure_file(linux.fragment.in "${CMAKE_CURRENT_BINARY_DIR}/linux.fragment" @ONLY)
file(MAKE_DIRECTORY "${_kbuild}")
if (NOT EXISTS "${_kdesc}")
    file(WRITE "${_kdesc}" "# placeholder - overwritten by every <name>-boot-elf build\n")
endif ()

set(_kllvm "LLVM=${_llvm_bindir}/" "CROSS_COMPILE=riscv64-linux-gnu-")
add_custom_command(OUTPUT "${_kbuild}/.config"
        COMMAND sh -c "test ! -f '${SOCT_BOOT_LINUX_DIR}/.config' || { echo 'linux: in-tree .config found; run: make -C ${SOCT_BOOT_LINUX_DIR} mrproper' >&2; exit 1; }"
        COMMAND ${_kenv} ${_boot_make} -C "${SOCT_BOOT_LINUX_DIR}" ARCH=riscv
        ${_kllvm} O=${_kbuild} ${_khostflags} defconfig
        COMMAND sh -c "cat '${CMAKE_CURRENT_BINARY_DIR}/linux.fragment' >> '${_kbuild}/.config'"
        COMMAND ${_kenv} ${_boot_make} -C "${SOCT_BOOT_LINUX_DIR}" ARCH=riscv
        ${_kllvm} O=${_kbuild} ${_khostflags} olddefconfig
        DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/linux.fragment"
        COMMENT "Configuring kernel (defconfig + linux.fragment)"
        VERBATIM)
# Custom-command outputs are only visible within their own directory; boot images are
# created in program subdirectories, so they reach the kernel config and the DTB through
# these targets instead of the files.
add_custom_target(kernel-config DEPENDS "${_kbuild}/.config")

set(_boot_dtb "${CMAKE_CURRENT_BINARY_DIR}/boot.dtb")
add_custom_command(OUTPUT "${_boot_dtb}"
        COMMAND ${DTC} -I dts -O dtb -p 4096 -o "${_boot_dtb}" "${SOCT_DTS}"
        DEPENDS "${SOCT_DTS}"
        COMMENT "Building padded boot DTB from ${SOCT_DTS}"
        VERBATIM)
add_custom_target(boot-dtb DEPENDS "${_boot_dtb}")

math(EXPR _fw_text_start "${SOCT_MEM_BASE_ADDR}" OUTPUT_FORMAT HEXADECIMAL)
math(EXPR _fw_fdt_addr "${SOCT_MEM_BASE_ADDR} + 128 * 1024 * 1024" OUTPUT_FORMAT HEXADECIMAL)
set(_sbibuild "${CMAKE_BINARY_DIR}/opensbi")

# The kernel/OpenSBI build dirs are shared state: every boot image funnels through the
# soct_kbuild pool so two images never build concurrently (honored by Ninja).
set_property(GLOBAL PROPERTY JOB_POOLS soct_kbuild=1)

# Create the boot-image target chain for a userspace program: <name>.BOOT.ELF in
# SOCT_ELFS_DIR, a kernel with the program as /init, wrapped in OpenSBI fw_payload.
# Called by initram.cmake from the program's directory scope. By default the initramfs is
# the standard description with the program's executable as /init; DESC substitutes an
# already-configured description of the caller's own (with DEPENDS naming the files and
# targets it packs) - the shell image's route.
function(soct_linux_add_boot_image name)
    cmake_parse_arguments(_bi "" "DESC" "DEPENDS" ${ARGN})
    if (_bi_DESC)
        set(_desc "${_bi_DESC}")
    else ()
        set(_desc "${CMAKE_CURRENT_BINARY_DIR}/${name}-initramfs.desc")
        # configure_file cannot expand $<TARGET_FILE:...>; the executable's path is
        # deterministic (current binary dir + name), so spell it out.
        set(INIT_ELF "${CMAKE_CURRENT_BINARY_DIR}/${name}")
        configure_file("${CMAKE_SOURCE_DIR}/initramfs.desc.in" "${_desc}" @ONLY)
        set(_bi_DEPENDS ${name})
    endif ()

    set(_out "${SOCT_ELFS_DIR}/${name}.BOOT.ELF")
    # An always-run custom TARGET, deliberately not a file-dated custom command: kernel
    # SOURCE changes (or the patches applied at configure) are invisible to the build
    # system's dependency graph, and a dated command would happily declare a stale image
    # current. kbuild is its own dependency tracker - re-entering it costs seconds when
    # there is nothing to do - and copy_if_different keeps the output content-stable.
    add_custom_target(${name}-boot-elf
            COMMAND ${CMAKE_COMMAND} -E copy_if_different "${_desc}" "${_kdesc}"
            COMMAND ${_kenv} ${_boot_make} -C "${SOCT_BOOT_LINUX_DIR}" ARCH=riscv
            ${_kllvm} O=${_kbuild} ${_khostflags} -j${_ncpu} Image
            COMMAND ${CMAKE_COMMAND} -E make_directory "${_sbibuild}"
            COMMAND ${CMAKE_COMMAND} -E rm -f
            "${_sbibuild}/platform/generic/firmware/fw_payload.o"
            "${_sbibuild}/platform/generic/firmware/fw_payload.elf"
            "${_sbibuild}/platform/generic/firmware/fw_payload.bin"
            COMMAND ${_boot_make} -C "${SOCT_BOOT_OPENSBI_DIR}" O=${_sbibuild}
            LLVM=${_llvm_bindir}/ "USE_LD_FLAG=--ld-path=${_llvm_lld}"
            PLATFORM=generic PLATFORM_RISCV_XLEN=64 READLINK=readlink
            FW_PIC=n FW_TEXT_START=${_fw_text_start} FW_JUMP=n FW_DYNAMIC=n
            FW_PAYLOAD=y FW_PAYLOAD_OFFSET=0x200000
            FW_PAYLOAD_PATH=${_kbuild}/arch/riscv/boot/Image
            FW_FDT_PATH=${_boot_dtb} FW_PAYLOAD_FDT_ADDR=${_fw_fdt_addr} -j${_ncpu}
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "${_sbibuild}/platform/generic/firmware/fw_payload.elf" "${_out}"
            COMMAND ${CMAKE_COMMAND} -E echo "${name}-boot-elf: ${_out} - copy to the SD card FAT root (as BOOT.ELF)"
            DEPENDS ${_bi_DEPENDS} "${_desc}"
            JOB_POOL soct_kbuild
            COMMENT "Building boot image ${name}.BOOT.ELF (kernel + ${name} as /init + OpenSBI)"
            VERBATIM)
    add_dependencies(${name}-boot-elf kernel-config boot-dtb)
endfunction()
