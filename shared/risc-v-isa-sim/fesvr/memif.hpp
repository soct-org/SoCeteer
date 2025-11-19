#pragma once
#include "types.hpp"
#include "asserts.hpp"

struct memif_t {
    using read_t = std::function<void(addr_t, size_t, uint8_t*)>;
    using write_t = std::function<void(addr_t, size_t, const uint8_t*)>;
    using log_rw_progress_t = std::function<void(bool)>;

    /// Read function. Takes an address, length, and a pointer to a buffer.
    read_t read = nullptr;

    /// Write function. Takes an address, length, and a const pointer to a buffer.
    write_t write = nullptr;

    /// Set whether to log progress of reads and writes.
    log_rw_progress_t log_rw_progress = nullptr;

    /// Write an integer to a memory address.
    template <std::integral T>
    void write_int(const addr_t addr, const T val) {
        massert(addr % sizeof(val) == 0, "misaligned address");
        massert(write != nullptr, "write function not set");
        write(addr, sizeof(T), reinterpret_cast<const uint8_t*>(&val));
    }

    /// Read an integer from a memory address.
    template <std::integral T>
    T read_int(const addr_t addr) {
        T val;
        massert(addr % sizeof(val) == 0, "misaligned address");
        massert(read != nullptr, "read function not set");
        read(addr, sizeof(T), reinterpret_cast<uint8_t*>(&val));
        return val;
    }
};

struct chunked_memif_t : memif_t {
    using read_chunk_t = std::function<void(addr_t, size_t, uint8_t*)>;
    using write_chunk_t = std::function<void(addr_t, size_t, const uint8_t*)>;
    using clear_chunk_t = std::function<void(addr_t, size_t)>;
    using chunk_align_t = std::function<size_t()>;
    using chunk_max_size_t = std::function<size_t()>;

    read_chunk_t read_chunk = nullptr;
    write_chunk_t write_chunk = nullptr;
    clear_chunk_t clear_chunk = nullptr;
    chunk_align_t chunk_align = nullptr;
    chunk_max_size_t chunk_max_size = nullptr;
};
