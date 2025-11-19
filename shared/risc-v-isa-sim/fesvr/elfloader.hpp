#pragma once

#include "elf.hpp"
#include "memif.hpp"

#include <filesystem>
#include <fstream>
#include <vector>
#include <map>
#include <cstring>
#include <stdexcept>

inline std::map<std::string, uint64_t> load_elf(const std::filesystem::path& filename,
                                                std::shared_ptr<memif_t> memif,
                                                reg_t* entry_point,
                                                reg_t load_offset) {
    if (!exists(filename)) {
        throw std::invalid_argument("ELF file does not exist: " + filename.string());
    }

    std::ifstream file(filename, std::ios::binary | std::ios::ate);
    if (!file) {
        throw std::runtime_error("Failed to open ELF file: " + filename.string());
    }

    std::size_t file_size = file.tellg();
    file.seekg(0, std::ios::beg);
    std::vector<char> buffer(file_size);

    if (!file.read(buffer.data(), static_cast<std::streamsize>(file_size))) {
        throw std::runtime_error("Failed to read ELF file: " + filename.string());
    }

    const auto* elf_header = reinterpret_cast<const Elf64_Ehdr*>(buffer.data());

    // ELF format checks
    if (!(is_elf32(*elf_header) || is_elf64(*elf_header))) {
        throw std::runtime_error("Invalid ELF format.");
    }

    if (!(matches_host(*elf_header))) {
        throw std::runtime_error("ELF file endianness does not match host.");
    }

    if (!(is_elf_exec(*elf_header) || is_elf_dyn(*elf_header))) {
        throw std::runtime_error("ELF file is not executable or shared object.");
    }

    if (!(is_elf_riscv(*elf_header) || is_elf_em_none(*elf_header))) {
        throw std::runtime_error("ELF file is not RISC-V or generic.");
    }

    if (!is_elf_vcurrent(*elf_header)) {
        throw std::runtime_error("Unsupported ELF version.");
    }

    if (is_elf_exec(*elf_header)) {
        load_offset = 0;
    }

    *entry_point = elf_header->e_entry + load_offset;

    std::vector<uint8_t> zero_padding;
    std::map<std::string, uint64_t> symbols;

    auto process_program_headers = [&](auto* header, auto* program_headers) {
        const auto e_phnum = header->e_phnum;
        for (unsigned i = 0; i < e_phnum; i++) {
            soct::logging::fesvr::debug << "Loading program headers (" << i << "/" << e_phnum << ")\n";
            if (auto& ph = program_headers[i]; ph.p_type == PT_LOAD && ph.p_memsz > 0) {
                reg_t segment_load_addr = ph.p_paddr + load_offset;

                if (ph.p_filesz > 0) {
                    memif->write(segment_load_addr, ph.p_filesz,
                                 reinterpret_cast<uint8_t*>(buffer.data()) + ph.p_offset);
                }

                if (size_t padding = ph.p_memsz - ph.p_filesz; padding > 0) {
                    zero_padding.resize(padding);
                    memif->write(segment_load_addr + ph.p_filesz, padding, zero_padding.data());
                }
            }
        }
    };

    auto process_symbol_table = [&](auto* header, auto* section_headers, const char* section_strtab) {
        unsigned strtab_idx = 0, symtab_idx = 0;
        const auto e_shnum = header->e_shnum;
        for (unsigned i = 0; i < e_shnum; i++) {
            soct::logging::fesvr::debug << "Loading symbol table (" << i << "/" << e_shnum << ")\n";
            auto& sh = section_headers[i];

            if (sh.sh_type & SHT_NOBITS) continue;

            if (std::strcmp(section_strtab + sh.sh_name, ".strtab") == 0) strtab_idx = i;
            if (std::strcmp(section_strtab + sh.sh_name, ".symtab") == 0) symtab_idx = i;
        }

        if (strtab_idx && symtab_idx) {
            const char* strtab = buffer.data() + section_headers[strtab_idx].sh_offset;
            auto* symtab = reinterpret_cast<const Elf64_Sym*>(buffer.data() + section_headers[symtab_idx].sh_offset);

            for (unsigned i = 0; i < section_headers[symtab_idx].sh_size / sizeof(Elf64_Sym); i++) {
                const char* sym_name = strtab + symtab[i].st_name;
                symbols[sym_name] = symtab[i].st_value + load_offset;
            }
        }
    };

    auto process_symbol_table32 = [&](const Elf32_Ehdr* header, const Elf32_Shdr* section_headers,
                                      const char* section_strtab) {
        unsigned strtab_idx = 0, symtab_idx = 0;
        const auto e_shnum = header->e_shnum;
        for (unsigned i = 0; i < e_shnum; i++) {
            auto& sh = section_headers[i];
            if (sh.sh_type & SHT_NOBITS) continue;
            if (std::strcmp(section_strtab + sh.sh_name, ".strtab") == 0) strtab_idx = i;
            if (std::strcmp(section_strtab + sh.sh_name, ".symtab") == 0) symtab_idx = i;
        }
        if (strtab_idx && symtab_idx) {
            const char* strtab = buffer.data() + section_headers[strtab_idx].sh_offset;
            auto* symtab = reinterpret_cast<const Elf32_Sym*>(buffer.data() + section_headers[symtab_idx].sh_offset);
            for (unsigned i = 0; i < section_headers[symtab_idx].sh_size / sizeof(Elf32_Sym); i++) {
                const char* sym_name = strtab + symtab[i].st_name;
                //store the symbol in the map
                symbols[sym_name] = symtab[i].st_value + load_offset;
            }
        }
    };


    if (is_elf32(*elf_header)) {
        auto* header = reinterpret_cast<const Elf32_Ehdr*>(buffer.data());
        auto* program_headers = reinterpret_cast<const Elf32_Phdr*>(buffer.data() + header->e_phoff);
        auto* section_headers = reinterpret_cast<const Elf32_Shdr*>(buffer.data() + header->e_shoff);
        const char* section_strtab = buffer.data() + section_headers[header->e_shstrndx].sh_offset;
        soct::logging::fesvr::info << "Loading program headers\n";
        process_program_headers(header, program_headers);
        soct::logging::fesvr::info << "Loading symbol table\n";
        process_symbol_table32(header, section_headers, section_strtab);
    } else {
        auto* header = reinterpret_cast<const Elf64_Ehdr*>(buffer.data());
        auto* program_headers = reinterpret_cast<const Elf64_Phdr*>(buffer.data() + header->e_phoff);
        auto* section_headers = reinterpret_cast<const Elf64_Shdr*>(buffer.data() + header->e_shoff);
        const char* section_strtab = buffer.data() + section_headers[header->e_shstrndx].sh_offset;
        soct::logging::fesvr::info << "Loading program headers\n";
        process_program_headers(header, program_headers);
        soct::logging::fesvr::info << "Loading symbol table\n";
        process_symbol_table(header, section_headers, section_strtab);
    }

    return symbols;
}
