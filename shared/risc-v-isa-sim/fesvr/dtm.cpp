#include "dtm.hpp"
#include <algorithm>
#include <cstring>
#include <iomanip>
#include "timepp.hpp"

void dtm_t::idle() {
    for (size_t i = 0; i < m_idle_cycles; i++) {
        nop();
    }
}

void dtm_t::reset() {
    massert(m_rv_info.has_value(), "System info has not been initialized");
    const auto num_harts = m_rv_info.value().num_harts;
    for (int hartsel = 0; hartsel < num_harts; hartsel++) {
        select_hart(hartsel);
        // this command also does a halt and resume
        fence_i();
        // after this command, the hart will run from _start.
        write_csr(0x7b1, entry());
    }
    // In theory any hart can handle the memory accesses, this will enforce that hart 0 handles them.
    select_hart(0);
    read_dtm(DM_DMSTATUS);
}


void dtm_t::read(addr_t addr, size_t len, uint8_t* bytes) {
    const size_t align = chunk_align();
    if (len && (addr & (align - 1))) {
        const auto this_len = std::min<size_t>(len, align - addr & (align - 1));
        uint8_t chunk[align];

        read_chunk(addr & ~(align - 1), align, chunk);
        memcpy(bytes, chunk + (addr & (align - 1)), this_len);

        bytes = bytes + this_len;
        addr += this_len;
        len -= this_len;
    }

    if (len & (align - 1)) {
        const size_t this_len = len & (align - 1);
        const size_t start = len - this_len;
        uint8_t chunk[align];

        read_chunk(addr + start, align, chunk);
        memcpy(bytes + start, chunk, this_len);

        len -= this_len;
    }

    // now we're aligned
    for (size_t pos = 0; pos < len; pos += chunk_max_size())
        read_chunk(addr + pos, std::min<size_t>(chunk_max_size(), len - pos), bytes + pos);
}

void dtm_t::write(addr_t addr, size_t len, const uint8_t* bytes) {
    const size_t align = chunk_align();
    if (len && (addr & (align - 1))) {
        const auto this_len = std::min<size_t>(len, align - (addr & align - 1));
        uint8_t chunk[align];

        read_chunk(addr & ~(align - 1), align, chunk);
        memcpy(chunk + (addr & (align - 1)), bytes, this_len);
        write_chunk(addr & ~(align - 1), align, chunk);

        bytes = bytes + this_len;
        addr += this_len;
        len -= this_len;
    }

    if (len & (align - 1)) {
        const size_t this_len = len & (align - 1);
        const size_t start = len - this_len;
        uint8_t chunk[align];

        read_chunk(addr + start, align, chunk);
        memcpy(chunk, bytes + start, this_len);
        write_chunk(addr + start, align, chunk);

        len -= this_len;
    }

    // now we're aligned
    bool all_zero = len != 0;
    for (size_t i = 0; i < len; i++)
        all_zero &= bytes[i] == 0;

    if (all_zero) {
        clear_chunk(addr, len);
    } else {
        size_t max_chunk = chunk_max_size();
        if (m_log_rw_progress) {soct::logging::fesvr::info.init_progress_bar("Write progress: ", "%");}
        for (size_t pos = 0; pos < len; pos += max_chunk) {
            if (m_log_rw_progress) {
                const auto percent = static_cast<float>(pos) / static_cast<float>(len) * 100;
                soct::logging::fesvr::info.update_progress_bar(static_cast<int>(percent));
            }
            write_chunk(addr + pos, std::min<size_t>(max_chunk, len - pos), (bytes + pos));
        }
        if (m_log_rw_progress) {soct::logging::fesvr::info.close_progress_bar();}
    }
}

void dtm_t::read_chunk(const uint64_t taddr, const size_t len, uint8_t* dst) {
    massert(m_rv_info.has_value(), "System info has not been initialized");
    const auto xlen = static_cast<int32_t>(m_rv_info.value().xlen);
    const auto xlen_bytes = xlen / 8;
    const auto xlen_words = xlen / 32;
    const auto ram_words = m_rv_info.value().ram_words;
    const auto data_words = m_rv_info.value().data_words;

    uint32_t prog[ram_words];
    uint32_t data[data_words];

    auto* curr = dst;

    halt(m_current_hart);

    const reg_t s0 = save_reg(RV_S0);
    const reg_t s1 = save_reg(RV_S1);

    prog[0] = rv_load(m_rv_info->xlen, RV_S1, RV_S0, 0);
    prog[1] = rv_addi(RV_S0, RV_S0, xlen_bytes);
    prog[2] = MATCH_EBREAK;

    data[0] = static_cast<uint32_t>(taddr);
    if (xlen > 32) {
        data[1] = static_cast<uint32_t>(taddr >> 32);
    }

    // Write s0 with the address, then execute program buffer.
    // This will get S1 with the data and increment s0.
    uint32_t command = AC_ACCESS_REGISTER_TRANSFER |
        AC_ACCESS_REGISTER_WRITE |
        AC_ACCESS_REGISTER_POSTEXEC |
        rv_ac_ar_size(xlen) |
        rv_ac_ar_regno(RV_S0);

    run_abstract_command(command, prog, 3, data, xlen_words);

    // TODO: could use autoexec here.
    for (size_t i = 0; i < (len * 8 / xlen); i++) {
        command = AC_ACCESS_REGISTER_TRANSFER |
            rv_ac_ar_size(xlen) |
            rv_ac_ar_regno(RV_S1);
        if ((i + 1) < (len * 8 / xlen)) {
            command |= AC_ACCESS_REGISTER_POSTEXEC;
        }

        run_abstract_command(command, nullptr, 0, data, xlen_words);

        memcpy(curr, data, xlen_bytes);
        curr += xlen_bytes;
    }

    restore_reg(RV_S0, s0);
    restore_reg(RV_S1, s1);

    resume(m_current_hart);
}

void dtm_t::write_chunk(const addr_t taddr, const size_t len, const uint8_t* src) {
    massert(m_rv_info.has_value(), "System info has not been initialized");
    const auto xlen = static_cast<int32_t>(m_rv_info.value().xlen);
    const auto xlen_bytes = xlen / 8;
    const auto xlen_words = xlen / 32;
    const auto ram_words = m_rv_info.value().ram_words;
    const auto data_words = m_rv_info.value().data_words;

    uint32_t prog[ram_words];
    uint32_t data[data_words];

    const uint8_t* curr = src;

    halt(m_current_hart);

    const reg_t s0 = save_reg(RV_S0);
    const reg_t s1 = save_reg(RV_S1);

    prog[0] = rv_store(m_rv_info->xlen, RV_S1, RV_S0, 0);
    prog[1] = rv_addi(RV_S0, RV_S0, xlen_bytes);
    prog[2] = MATCH_EBREAK;

    data[0] = static_cast<uint32_t>(taddr);
    if (xlen > 32) {
        data[1] = static_cast<uint32_t>(taddr >> 32);
    }

    // Write the program (not used yet).
    // Write s0 with the address.
    uint32_t command = AC_ACCESS_REGISTER_TRANSFER |
        AC_ACCESS_REGISTER_WRITE |
        rv_ac_ar_size(xlen) |
        rv_ac_ar_regno(RV_S0);

    run_abstract_command(command, prog, 3, data, xlen_words);

    // Use Autoexec for more than one word of transfer.
    // Write S1 with data, then execution stores S1 to
    // 0(S0) and increments S0.
    // Each time we write XLEN bits.
    memcpy(data, curr, xlen_bytes);
    curr += xlen_bytes;

    command = AC_ACCESS_REGISTER_TRANSFER |
        AC_ACCESS_REGISTER_POSTEXEC |
        AC_ACCESS_REGISTER_WRITE |
        rv_ac_ar_size(xlen) |
        rv_ac_ar_regno(RV_S1);

    run_abstract_command(command, nullptr, 0, data, xlen_words);
    uint32_t abstractcs;

    for (size_t i = 1; i < (len * 8 / xlen); i++) {
        if (i == 1) {
            write_dtm(DM_ABSTRACTAUTO, 1 << DM_ABSTRACTAUTO_AUTOEXECDATA_OFFSET);
        }
        memcpy(data, curr, xlen_bytes);
        curr += xlen_bytes;
        if (xlen == 64) {
            write_dtm(DM_DATA0 + 1, data[1]);
        }
        write_dtm(DM_DATA0, data[0]); //Triggers a command w/ autoexec.

        do {
            abstractcs = read_dtm(DM_ABSTRACTCS);
        } while (abstractcs & DM_ABSTRACTCS_BUSY);
        if (get_field(abstractcs, DM_ABSTRACTCS_CMDERR)) {
            die(get_field(abstractcs, DM_ABSTRACTCS_CMDERR));
        }
    }
    if ((len * 8 / xlen) > 1) {
        write_dtm(DM_ABSTRACTAUTO, 0);
    }

    restore_reg(RV_S0, s0);
    restore_reg(RV_S1, s1);
    resume(m_current_hart);
}

void dtm_t::clear_chunk(addr_t taddr, size_t len) {
    massert(m_rv_info.has_value(), "System info has not been initialized");
    const auto xlen = static_cast<int32_t>(m_rv_info.value().xlen);
    const auto xlen_bytes = xlen / 8;
    const auto xlen_words = xlen / 32;
    const auto ram_words = m_rv_info.value().ram_words;
    const auto data_words = m_rv_info.value().data_words;

    uint32_t prog[ram_words];
    uint32_t data[data_words];

    halt(m_current_hart);
    const reg_t s0 = save_reg(RV_S0);
    const reg_t s1 = save_reg(RV_S1);

    // S0 = Addr
    data[0] = static_cast<uint32_t>(taddr);
    data[1] = static_cast<uint32_t>(taddr >> 32);
    uint32_t command = AC_ACCESS_REGISTER_TRANSFER |
        AC_ACCESS_REGISTER_WRITE |
        rv_ac_ar_size(xlen) |
        rv_ac_ar_regno(RV_S0);
    run_abstract_command(command, nullptr, 0, data, xlen_words);

    // S1 = Addr + len, loop until S0 = S1
    prog[0] = rv_store(m_rv_info->xlen, RV_X0, RV_S0, 0);
    prog[1] = rv_addi(RV_S0, RV_S0, xlen_bytes);
    prog[2] = rv_bne(RV_S0, RV_S1, 0 * 4, 2 * 4);
    prog[3] = MATCH_EBREAK;

    data[0] = static_cast<uint32_t>(taddr + len);
    data[1] = static_cast<uint32_t>((taddr + len) >> 32);
    command = AC_ACCESS_REGISTER_TRANSFER |
        AC_ACCESS_REGISTER_WRITE |
        rv_ac_ar_size(xlen) |
        rv_ac_ar_regno(RV_S1) |
        AC_ACCESS_REGISTER_POSTEXEC;
    run_abstract_command(command, prog, 4, data, xlen_words);

    restore_reg(RV_S0, s0);
    restore_reg(RV_S1, s1);

    resume(m_current_hart);
}

size_t dtm_t::chunk_align() const {
    massert(m_rv_info.has_value(), "System info has not been initialized");
    const auto xlen = m_rv_info.value().xlen;
    return xlen / 8;
}

size_t dtm_t::chunk_max_size() const {
    // Arbitrary choice. 4k Page size seems reasonable.
    return 4096;
}

std::shared_ptr<chunked_memif_t> dtm_t::init_chunked_memif() {
    auto cmemif = std::make_shared<chunked_memif_t>();
    cmemif->read = [this]<typename... T>(T&&... args) { return timefn(read(std::forward<T>(args)...)); };
    cmemif->write = [this]<typename... T>(T&&... args){ return timefn(write(std::forward<T>(args)...)); };
    cmemif->log_rw_progress = [this]<typename... T>(T&&... args) {return log_rw_progress(std::forward<T>(args)...);};
    cmemif->read_chunk = [this]<typename... T>(T&&... args) { return timefn(read_chunk(std::forward<T>(args)...)); };
    cmemif->write_chunk = [this]<typename... T>(T&&... args) { return timefn(write_chunk(std::forward<T>(args)...)); };
    cmemif->clear_chunk = [this]<typename... T>(T&&... args) { return timefn(clear_chunk(std::forward<T>(args)...)); };
    cmemif->chunk_align = [this] { return timefn(chunk_align()); };
    cmemif->chunk_max_size = [this] { return timefn(chunk_max_size()); };

    return cmemif;
}
