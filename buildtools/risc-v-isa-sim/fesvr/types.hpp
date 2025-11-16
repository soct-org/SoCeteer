#pragma once
#include <cinttypes>
#include <functional>
#include <debug_defines.h>
#include "asserts.hpp"
#include <semaphore>

/// A register type
using reg_t = uint64_t;

/// A signed register type
using sreg_t = int64_t;

/// An address type
using addr_t = reg_t;

/// A function pointer to a syscall function
using syscall_func_t = std::function<reg_t(reg_t, reg_t, reg_t, reg_t, reg_t, reg_t, reg_t)>;

/// The maximum path size
inline constexpr size_t MAX_PATH_SIZE = 4096;

/// Callback function type
using callback_t = std::function<void(uint64_t)>;

/// The file descriptor for the current working directory
inline constexpr int32_t RV_AT_FDCWD = -100;

inline constexpr uint32_t RV_CSR_WRITE = 1;

inline constexpr uint32_t RV_CSR_SET = 2;

inline constexpr uint32_t RV_CSR_CLEAR = 3;

inline constexpr uint32_t RV_X0 = 0;

inline constexpr uint32_t RV_S0 = 8;

inline constexpr uint32_t RV_S1 = 9;

/// Contains the string representations of the RISC-V error codes
constexpr std::string_view rv_error_codes[] = {"OK", "BUSY", "NOT_SUPPORTED", "EXCEPTION", "HALT/RESUME"};

/**
 * @brief Encodes a register number for the Abstract Command (AC) interface.
 *
 * This macro sets bit 12 (0x1000) to indicate an abstract register
 * and shifts it into the appropriate position defined by
 * AC_ACCESS_REGISTER_REGNO_OFFSET.
 *
 * @param x The register number to encode.
 * @return Encoded register number with AC-specific formatting.
 */
constexpr uint32_t rv_ac_ar_regno(uint32_t x) {
    return (0x1000 | x) << AC_ACCESS_REGISTER_REGNO_OFFSET;
}

/**
 * @brief Encodes the access size for the Abstract Command register interface.
 *
 * This macro determines the correct 2-bit encoding based on the register width:
 * - 2 for 32-bit
 * - 3 for 64-bit
 * - 4 for 128-bit
 *
 * The result is then shifted into the appropriate position defined by
 * AC_ACCESS_REGISTER_AARSIZE_OFFSET.
 *
 * @param x The register width in bits (32, 64, or 128).
 * @return Encoded size field for AC access register commands.
 */
constexpr uint32_t rv_ac_ar_size(uint32_t x) {
    return ((x == 128) ? 4 : (x == 64 ? 3 : 2)) << AC_ACCESS_REGISTER_AARSIZE_OFFSET;
}

/**
 * @brief Encodes a CSR (Control and Status Register) instruction for RISC-V.
 *
 * This macro generates a 32-bit instruction encoding for CSR-related operations,
 * such as CSRRS (read and set), CSRRC (read and clear), and CSRRW (read and write).
 *
 * The instruction format follows the RISC-V encoding:
 * - Opcode (0x73) for system instructions
 * - `type` (bits 14:12) specifies the exact CSR operation
 * - `dst` (bits 11:7) is the destination register
 * - `src` (bits 19:15) is the source register
 * - `csr` (bits 31:20) is the CSR address
 *
 * @param type The type of CSR operation (CSRRW = 1, CSRRS = 2, CSRRC = 3).
 * @param dst The destination register (rd).
 * @param csr The CSR register address.
 * @param src The source register (rs1).
 * @return Encoded 32-bit instruction for CSR operations.
 */
constexpr uint32_t rv_csrrx(uint32_t type, uint32_t dst, uint32_t csr, uint32_t src) {
    return 0x73 | (type << 12) | (dst << 7) | (src << 15) | (csr << 20);
}

/**
 * @brief Extracts a bit field from a given value.
 *
 * This macro shifts `x` right by `s` bits and extracts an `n`-bit field.
 *
 * @param x The input value.
 * @param s The starting bit position.
 * @param n The number of bits to extract.
 * @return The extracted bit field.
 */
constexpr uint32_t rv_extract(uint32_t x, uint32_t s, uint32_t n) {
    return (x >> s) & ((1 << n) - 1);
}

/**
 * @brief Encodes an immediate value for I-type instructions in RISC-V.
 *
 * Extracts the lower 12 bits of `x` and shifts them to bit position 20.
 *
 * @param x The immediate value.
 * @return The encoded I-type immediate.
 */
constexpr uint32_t rv_encode_itype_imm(uint32_t x) {
    return rv_extract(x, 0, 12) << 20;
}

/**
 * @brief Encodes an immediate value for S-type instructions in RISC-V.
 *
 * Splits the immediate into two parts:
 * - Bits [4:0] go to position 7 (low part).
 * - Bits [11:5] go to position 25 (high part).
 *
 * @param x The immediate value.
 * @return The encoded S-type immediate.
 */
constexpr uint32_t rv_encode_stype_imm(uint32_t x) {
    return (rv_extract(x, 0, 5) << 7) | (rv_extract(x, 5, 7) << 25);
}

/**
 * @brief Encodes an immediate value for SB-type (branch) instructions in RISC-V.
 *
 * The immediate is split as follows:
 * - Bits [4:1] go to position 8.
 * - Bits [10:5] go to position 25.
 * - Bit [11] goes to position 7.
 * - Bit [12] (sign bit) goes to position 31.
 *
 * @param x The immediate value.
 * @return The encoded SB-type immediate.
 */
constexpr uint32_t rv_encode_sbtype_imm(uint32_t x) {
    return (rv_extract(x, 1, 4) << 8) | (rv_extract(x, 5, 6) << 25) | (rv_extract(x, 11, 1) << 7) | (
        rv_extract(x, 12, 1) << 31);
}

/**
 * @brief Encodes an immediate value for U-type instructions in RISC-V.
 *
 * The upper 20 bits of `x` are shifted to position 12.
 *
 * @param x The immediate value.
 * @return The encoded U-type immediate.
 */
constexpr uint32_t rv_encode_utype_imm(uint32_t x) {
    return rv_extract(x, 12, 20) << 12;
}

/**
 * @brief Encodes an immediate value for UJ-type (jump) instructions in RISC-V.
 *
 * The immediate is split as follows:
 * - Bits [10:1] go to position 21.
 * - Bit [11] goes to position 20.
 * - Bits [19:12] go to position 12.
 * - Bit [20] (sign bit) goes to position 31.
 *
 * @param x The immediate value.
 * @return The encoded UJ-type immediate.
 */
constexpr uint32_t rv_encode_ujtype_imm(uint32_t x) {
    return (rv_extract(x, 1, 10) << 21) | (rv_extract(x, 11, 1) << 20) | (rv_extract(x, 12, 8) << 12) | (
        rv_extract(x, 20, 1) << 31);
}


/**
 * @brief Encodes a LOAD instruction for RISC-V.
 *
 * Generates a load instruction (LW for 32-bit or LD for 64-bit) based on `xlen`.
 * - Uses opcode `0x00002003` for 32-bit loads (LW).
 * - Uses opcode `0x00003003` for 64-bit loads (LD).
 *
 * The instruction is encoded as:
 * - Destination register (`dst`) at bits [11:7].
 * - Base register (`base`) at bits [19:15].
 * - Immediate offset (`imm`) encoded using `ENCODE_ITYPE_IMM`.
 *
 * @param xlen The register width (32-bit or 64-bit).
 * @param dst The destination register.
 * @param base The base register.
 * @param imm The immediate offset.
 * @return The encoded LOAD instruction.
 */
constexpr uint32_t rv_load(uint32_t xlen, uint32_t dst, uint32_t base, uint32_t imm) {
    return ((xlen == 64 ? 0x00003003 : 0x00002003) |
        (dst << 7) | (base << 15) | rv_encode_itype_imm(imm));
}

/**
 * @brief Encodes a STORE instruction for RISC-V.
 *
 * Generates a store instruction (SW for 32-bit or SD for 64-bit) based on `xlen`.
 * - Uses opcode `0x00002023` for 32-bit stores (SW).
 * - Uses opcode `0x00003023` for 64-bit stores (SD).
 *
 * The instruction is encoded as:
 * - Source register (`src`) at bits [24:20].
 * - Base register (`base`) at bits [19:15].
 * - Immediate offset (`imm`) encoded using `ENCODE_STYPE_IMM`.
 *
 * @param xlen The register width (32-bit or 64-bit).
 * @param src The source register.
 * @param base The base register.
 * @param imm The immediate offset.
 * @return The encoded STORE instruction.
 */
constexpr uint32_t rv_store(uint32_t xlen, uint32_t src, uint32_t base, uint32_t imm) {
    return ((xlen == 64 ? 0x00003023 : 0x00002023) |
        (src << 20) | (base << 15) | rv_encode_stype_imm(imm));
}

/**
 * @brief Generates a JUMP (JAL) instruction.
 *
 * This function encodes a Jump and Link (JAL) instruction in RISC-V.
 * It computes the relative offset and formats it into the UJ-type encoding.
 *
 * @param target The target address to jump to.
 * @param current The current address of the instruction.
 * @return Encoded 32-bit JAL instruction.
 */
constexpr uint32_t rv_jump(int32_t target, int32_t current) {
    return 0x6F | rv_encode_ujtype_imm(target - current);
}

/**
 * @brief Generates a BNE (Branch Not Equal) instruction.
 *
 * This function encodes a Branch Not Equal (BNE) instruction in RISC-V.
 * It calculates the relative offset and formats it into the SB-type encoding.
 *
 * @param r1 The first source register.
 * @param r2 The second source register.
 * @param target The target address for the branch.
 * @param current The current address of the instruction.
 * @return Encoded 32-bit BNE instruction.
 */
constexpr uint32_t rv_bne(uint32_t r1, uint32_t r2, int32_t target, int32_t current) {
    return 0x1063 | (r1 << 15) | (r2 << 20) | rv_encode_sbtype_imm(target - current);
}

/**
 * @brief Generates an ADDI (Add Immediate) instruction.
 *
 * This function encodes an Add Immediate (ADDI) instruction in RISC-V.
 * It takes a destination register, a source register, and an immediate value.
 * The immediate value is encoded using I-type instruction encoding.
 *
 * @param dst The destination register.
 * @param src The source register.
 * @param imm The immediate value to be added.
 * @return Encoded 32-bit ADDI instruction.
 */
constexpr uint32_t rv_addi(uint32_t dst, uint32_t src, int32_t imm) {
    return 0x13 | (dst << 7) | (src << 15) | rv_encode_itype_imm(imm);
}

/**
 * @brief Generates an SRL (Shift Right Logical) instruction.
 *
 * This function encodes a Shift Right Logical (SRL) instruction in RISC-V.
 * It shifts the contents of the source register right by a given shift amount.
 *
 * @param dst The destination register.
 * @param src The source register.
 * @param sh The shift amount.
 * @return Encoded 32-bit SRL instruction.
 */
constexpr uint32_t rv_srl(uint32_t dst, uint32_t src, uint32_t sh) {
    return 0x5033 | (dst << 7) | (src << 15) | (sh << 20);
}
