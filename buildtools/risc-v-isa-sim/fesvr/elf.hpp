#pragma once
#include <cstdint>
#include <bit>

constexpr uint16_t ET_EXEC = 2;
constexpr uint16_t ET_DYN = 3;
constexpr uint16_t EM_RISCV = 243;
constexpr uint16_t EM_NONE = 0;
constexpr uint32_t EV_CURRENT = 1;
constexpr uint32_t PT_LOAD = 1;
constexpr uint32_t SHT_NOBITS = 8;

typedef struct {
    uint8_t e_ident[16];
    uint16_t e_type;
    uint16_t e_machine;
    uint32_t e_version;
    uint32_t e_entry;
    uint32_t e_phoff;
    uint32_t e_shoff;
    uint32_t e_flags;
    uint16_t e_ehsize;
    uint16_t e_phentsize;
    uint16_t e_phnum;
    uint16_t e_shentsize;
    uint16_t e_shnum;
    uint16_t e_shstrndx;
} Elf32_Ehdr;

typedef struct {
    uint32_t sh_name;
    uint32_t sh_type;
    uint32_t sh_flags;
    uint32_t sh_addr;
    uint32_t sh_offset;
    uint32_t sh_size;
    uint32_t sh_link;
    uint32_t sh_info;
    uint32_t sh_addralign;
    uint32_t sh_entsize;
} Elf32_Shdr;

typedef struct {
    uint32_t p_type;
    uint32_t p_offset;
    uint32_t p_vaddr;
    uint32_t p_paddr;
    uint32_t p_filesz;
    uint32_t p_memsz;
    uint32_t p_flags;
    uint32_t p_align;
} Elf32_Phdr;

typedef struct {
    uint32_t st_name;
    uint32_t st_value;
    uint32_t st_size;
    uint8_t st_info;
    uint8_t st_other;
    uint16_t st_shndx;
} Elf32_Sym;

typedef struct {
    uint8_t e_ident[16];
    uint16_t e_type;
    uint16_t e_machine;
    uint32_t e_version;
    uint64_t e_entry;
    uint64_t e_phoff;
    uint64_t e_shoff;
    uint32_t e_flags;
    uint16_t e_ehsize;
    uint16_t e_phentsize;
    uint16_t e_phnum;
    uint16_t e_shentsize;
    uint16_t e_shnum;
    uint16_t e_shstrndx;
} Elf64_Ehdr;

typedef struct {
    uint32_t sh_name;
    uint32_t sh_type;
    uint64_t sh_flags;
    uint64_t sh_addr;
    uint64_t sh_offset;
    uint64_t sh_size;
    uint32_t sh_link;
    uint32_t sh_info;
    uint64_t sh_addralign;
    uint64_t sh_entsize;
} Elf64_Shdr;

typedef struct {
    uint32_t p_type;
    uint32_t p_flags;
    uint64_t p_offset;
    uint64_t p_vaddr;
    uint64_t p_paddr;
    uint64_t p_filesz;
    uint64_t p_memsz;
    uint64_t p_align;
} Elf64_Phdr;

typedef struct {
    uint32_t st_name;
    uint8_t st_info;
    uint8_t st_other;
    uint16_t st_shndx;
    uint64_t st_value;
    uint64_t st_size;
} Elf64_Sym;


template <typename T>
concept ElfHeader = std::is_same_v<T, Elf32_Ehdr> || std::is_same_v<T, Elf64_Ehdr>;


inline bool is_elf(const Elf32_Ehdr& hdr) {
    return hdr.e_ident[0] == 0x7f && hdr.e_ident[1] == 'E' &&
           hdr.e_ident[2] == 'L' && hdr.e_ident[3] == 'F';
}

inline bool is_elf(const Elf64_Ehdr& hdr) {
    return hdr.e_ident[0] == 0x7f && hdr.e_ident[1] == 'E' &&
           hdr.e_ident[2] == 'L' && hdr.e_ident[3] == 'F';
}

template <ElfHeader T>
inline bool is_elf32(const T& hdr) {
    return is_elf(hdr) && hdr.e_ident[4] == 1;
}

template <ElfHeader T>
inline bool is_elf64(const T& hdr) {
    return is_elf(hdr) && hdr.e_ident[4] == 2;
}

template <ElfHeader T>
inline bool is_elf_le(const T& hdr) {
    return is_elf(hdr) && hdr.e_ident[5] == 1;
}

template <ElfHeader T>
inline bool is_elf_be(const T& hdr) {
    return is_elf(hdr) && hdr.e_ident[5] == 2;
}

template <ElfHeader T>
inline bool matches_host(const T& hdr) {
    if (std::endian::native == std::endian::little) {
        return is_elf_le(hdr);
    } else {
        return is_elf_be(hdr);
    }
}

template <ElfHeader T>
inline bool is_elf_exec(const T& hdr) {
    return is_elf(hdr) && hdr.e_type == ET_EXEC;
}

template <ElfHeader T>
inline bool is_elf_dyn(const T& hdr) {
    return is_elf(hdr) && hdr.e_type == ET_DYN;
}

template <ElfHeader T>
inline bool is_elf_riscv(const T& hdr) {
    return is_elf(hdr) && hdr.e_machine == EM_RISCV;
}

template <ElfHeader T>
inline bool is_elf_em_none(const T& hdr) {
    return is_elf(hdr) && hdr.e_machine == EM_NONE;
}

template <ElfHeader T>
inline bool is_elf_vcurrent(const T& hdr) {
    return is_elf(hdr) && hdr.e_version == EV_CURRENT;
}
