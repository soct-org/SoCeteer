#pragma once

#include <cassert>
#include <cstdint>
#include <thread>
#include <condition_variable>
#include <mutex>

#include "verilated.h"
#include "debug_defines.h"
#include "encoding.h"
#include "htif.hpp"
#include "asserts.hpp"
#include "logging.hpp"
#include "elfloader.hpp"
#include "misc/mt_q.hpp"

class dtm_t final : public htif_t {
public:
    /// A response object for the DTM.
    struct resp_t {
        uint32_t resp;
        uint32_t data;

        [[nodiscard]] std::string to_string() const {
            return "resp_t { resp: " + std::to_string(resp) +
                ", data: " + std::to_string(data) + " }";
        }
    };

    /// A request object for the DTM.
    struct req_t {
        uint32_t addr;
        uint32_t op;
        uint32_t data;

        [[nodiscard]] std::string to_string() const {
            return "req_t { addr: " + std::to_string(addr) +
                ", op: " + std::to_string(op) +
                ", data: " + std::to_string(data) + " }";
        }
    };

    /// Contains information about the RISC-V system.
    struct rv_info_t {
        // The base address of the data registers.
        uint32_t data_base;
        // The XLEN of the system.
        uint32_t xlen;
        // The number of words in the RAM.
        size_t ram_words;
        // Number of `data` registers that are implemented as part of the abstract command interface
        size_t data_words;
        // The number of harts in the system.
        size_t num_harts;

        [[nodiscard]] std::string to_string() const {
            return "rv_info { data_base: " + std::to_string(data_base) +
                ", xlen: " + std::to_string(xlen) +
                ", ram_words: " + std::to_string(ram_words) +
                ", data_words: " + std::to_string(data_words) +
                ", num_harts: " + std::to_string(num_harts) + " }";
        }
    };

    dtm_t(const int argc, char** argv) : htif_t(argc, argv, init_chunked_memif()) {
        m_host_thread = std::thread(&dtm_t::thread_main, this);
        m_host_thread.detach();
    }

    ~dtm_t() override {
        m_running = false;
    }

    /// Handle a tick from the target. If called multiple times in the same cycle, set `called_again` to true.
    void tick(const bool target_ready, const bool target_resp_valid, const resp_t resp_bits,
              const bool called_again = false) {
        m_target_ready = target_ready;

        if (!called_again) {
            TRACE(cycle++;)
        }

        // handle the response first, we might be able to enqueue a new request in the same cycle
        if (m_state == WAITING_RESP && target_resp_valid) {
            TRACE(soct::logging::fesvr::trace << "Enqueued response at cycle " << cycle << ": " << resp_bits.to_string()
                << "\n";)
            m_resp_buf.push(resp_bits);
            m_state = WAITING_REQ;
        }

        if (m_state == WAITING_REQ && target_ready && m_req_buf.has_value()) {
            TRACE(soct::logging::fesvr::trace << "Staging request at cycle " << cycle << ": " << m_req_buf.front().
                to_string() << "\n";)
            m_req = m_req_buf.pop();
            m_state = WAITING_RESP;
        }
    }

    // Whether the response is ready - always true.
    static bool resp_ready() {
        return true;
    }

    [[nodiscard]] bool req_valid() const {
        return m_state == WAITING_RESP && m_target_ready;
    }

    [[nodiscard]] req_t req_bits() const {
        return m_req;
    }

protected:
    void reset() override;
    void idle() override;

private:
    void thread_main() {
        // Enable the debugger.
        write_dtm(DM_DMCONTROL, DM_DMCONTROL_DMACTIVE);
        // Poll until the debugger agrees it's enabled.
        while ((read_dtm(DM_DMCONTROL) & DM_DMCONTROL_DMACTIVE) == 0) {
        }
        rv_info_t rv_info{}; // Initialize the system information.
        uint32_t abstractcs = read_dtm(DM_ABSTRACTCS);
        rv_info.ram_words = get_field(abstractcs, DM_ABSTRACTCS_PROGBUFSIZE);
        rv_info.data_words = get_field(abstractcs, DM_ABSTRACTCS_DATACOUNT);
        uint32_t hartinfo = read_dtm(DM_HARTINFO);
        massert(get_field(hartinfo, DM_HARTINFO_NSCRATCH) > 0, "No scratch registers");
        massert(get_field(hartinfo, DM_HARTINFO_DATAACCESS), "No data access");
        rv_info.data_base = get_field(hartinfo, DM_HARTINFO_DATAADDR);
        rv_info.num_harts = enumerate_harts();
        halt(0);
        rv_info.xlen = get_xlen();
        resume(0);
        soct::logging::fesvr::info << "Detected " << rv_info.num_harts << " harts\n";
        soct::logging::fesvr::info << "XLEN: " << rv_info.xlen << "\n";
        soct::logging::fesvr::debug << "System: " << rv_info.to_string() << "\n";
        m_rv_info = rv_info; // Copy the system information to the class variable.
        m_running = true;
        run();
        vl_finish(__FILE__, __LINE__, "");
    }

    /// Send a request to the DTM and wait for a response.
    uint32_t send_req(const req_t& r) {
        m_req_buf.push(r);
        TRACE(soct::logging::fesvr::trace << "Enqueued request at cycle " << cycle << ": " << r.to_string() << "\n";)
        m_resp_buf.wait_for_value();
        TRACE(soct::logging::fesvr::trace << "Got response at cycle " << cycle << ": " << m_resp_buf.front().to_string()
            << "\n";)
        const auto [resp, data] = m_resp_buf.pop();
        massert(resp == 0, "Response is not OK");
        return data;
    }

    /// No operation.
    void nop() {
        send_req({0, 0, 0});
    }

    /// Read a value from a DTM register.
    uint32_t read_dtm(const uint32_t addr) {
        return send_req({addr, 1, 0});
    }

    /// Write a value to a DTM register.
    uint32_t write_dtm(const uint32_t addr, const uint32_t data) {
        return send_req({addr, 2, data});
    }

    uint64_t modify_csr(const uint32_t which, const uint64_t value, const uint32_t type) {
        massert(m_rv_info.has_value(), "System info has not been initialized");
        halt(m_current_hart);
        const auto xlen = m_rv_info.value().xlen;
        const auto data_base = m_rv_info.value().data_base;
        const uint32_t prog[] = {
            rv_csrrx(RV_CSR_WRITE, RV_S0, CSR_DSCRATCH0, RV_S0),
            rv_load(m_rv_info->xlen, RV_S0, RV_X0, data_base),
            rv_csrrx(type, RV_S0, which, RV_S0),
            rv_store(m_rv_info->xlen, RV_S0, RV_X0, data_base),
            rv_csrrx(RV_CSR_WRITE, RV_S0, CSR_DSCRATCH0, RV_S0),
            MATCH_EBREAK
        };

        uint32_t data[] = {static_cast<uint32_t>(value), static_cast<uint32_t>(value >> 32)};

        const uint32_t command = AC_ACCESS_REGISTER_POSTEXEC |
            AC_ACCESS_REGISTER_TRANSFER |
            AC_ACCESS_REGISTER_WRITE |
            rv_ac_ar_size(xlen) |
            rv_ac_ar_regno(RV_X0);

        run_abstract_command(command, prog, std::size(prog), data, xlen / (4 * 8));

        uint64_t res = read_dtm(DM_DATA0);
        if (xlen == 64)
            res |= read_dtm(DM_DATA0 + 1);

        resume(m_current_hart);
        return res;
    }

    uint64_t write_csr(const uint32_t which, const uint64_t value) {
        return modify_csr(which, value, RV_CSR_WRITE);
    }

    uint64_t set_csr(const uint32_t which, const uint64_t value) {
        return modify_csr(which, value, RV_CSR_SET);
    }

    uint64_t clear_csr(const uint32_t which, const uint64_t value) {
        return modify_csr(which, value, RV_CSR_CLEAR);
    }

    uint64_t read_csr(const uint32_t which) {
        return set_csr(which, 0);
    }

    /// Select a hart, given its index.
    void select_hart(const size_t hartsel) {
        uint32_t dmcontrol = read_dtm(DM_DMCONTROL);
        write_dtm(DM_DMCONTROL, set_field(dmcontrol, DM_DMCONTROL_HASEL, hartsel));
        m_current_hart = hartsel;
    }

    /// Return the number of harts in the system.
    size_t enumerate_harts() {
        int max_hart = (1 << DM_DMCONTROL_HASEL_LENGTH) - 1;
        write_dtm(DM_DMCONTROL, set_field(read_dtm(DM_DMCONTROL), DM_DMCONTROL_HASEL, max_hart));
        read_dtm(DM_DMSTATUS);
        max_hart = get_field(read_dtm(DM_DMCONTROL), DM_DMCONTROL_HASEL);

        size_t hartsel = 0;
        for (; hartsel <= max_hart; hartsel++) {
            select_hart(hartsel);
            uint32_t dmstatus = read_dtm(DM_DMSTATUS);
            if (get_field(dmstatus, DM_DMSTATUS_ANYNONEXISTENT))
                break;
        }
        select_hart(m_current_hart);

        return hartsel;
    }

    /// Resume a hart.
    void resume(const size_t hartsel) {
        uint32_t dmcontrol = DM_DMCONTROL_RESUMEREQ | DM_DMCONTROL_DMACTIVE;
        dmcontrol = set_field(dmcontrol, DM_DMCONTROL_HASEL, hartsel);
        write_dtm(DM_DMCONTROL, dmcontrol);
        uint32_t dmstatus;
        do {
            dmstatus = read_dtm(DM_DMSTATUS);
        } while (get_field(dmstatus, DM_DMSTATUS_ALLRESUMEACK) == 0);
        dmcontrol &= ~DM_DMCONTROL_RESUMEREQ;
        write_dtm(DM_DMCONTROL, dmcontrol);
        // Read dmstatus to avoid back-to-back writes to dmcontrol.
        read_dtm(DM_DMSTATUS);
        m_current_hart = hartsel;
        write_dtm(DM_DMCONTROL, DM_DMCONTROL_DMACTIVE);
        // Read dmstatus to avoid back-to-back writes to dmcontrol.
        read_dtm(DM_DMSTATUS);
    }

    /// Halt a hart.
    void halt(const size_t hartsel) {
        write_dtm(DM_DMCONTROL, DM_DMCONTROL_DMACTIVE);
        // Read dmstatus to avoid back-to-back writes to dmcontrol.
        read_dtm(DM_DMSTATUS);

        uint32_t dmcontrol = DM_DMCONTROL_HALTREQ | DM_DMCONTROL_DMACTIVE;
        dmcontrol = set_field(dmcontrol, DM_DMCONTROL_HASEL, hartsel);
        write_dtm(DM_DMCONTROL, dmcontrol);
        uint32_t dmstatus;
        do {
            dmstatus = read_dtm(DM_DMSTATUS);
        } while (get_field(dmstatus, DM_DMSTATUS_ALLHALTED) == 0);
        dmcontrol &= ~DM_DMCONTROL_HALTREQ;
        write_dtm(DM_DMCONTROL, dmcontrol);
        // Read dmstatus to avoid back-to-back writes to dmcontrol.
        read_dtm(DM_DMSTATUS);
        m_current_hart = hartsel;
    }

    /// Run an abstract command.
    uint32_t run_abstract_command(const uint32_t command,
                                  const uint32_t program[] = nullptr,
                                  const size_t program_n = 0,
                                  uint32_t data[] = nullptr,
                                  const size_t data_n = 0,
                                  const bool log_error = true) {
        if (m_rv_info.has_value()) {
            massert(program_n <= m_rv_info.value().ram_words, "Program size exceeds RAM size");
            massert(data_n <= m_rv_info.value().data_words, "Data size exceeds data words");
        }

        for (size_t i = 0; i < program_n && program; i++) {
            write_dtm(DM_PROGBUF0 + i, program[i]);
        }

        if (get_field(command, AC_ACCESS_REGISTER_WRITE) &&
            get_field(command, AC_ACCESS_REGISTER_TRANSFER)) {
            for (size_t i = 0; i < data_n && data; i++) {
                write_dtm(DM_DATA0 + i, data[i]);
            }
        }

        write_dtm(DM_COMMAND, command);

        // Wait for not busy and then check for error.
        uint32_t abstractcs;
        do {
            abstractcs = read_dtm(DM_ABSTRACTCS);
        } while (abstractcs & DM_ABSTRACTCS_BUSY);

        if ((get_field(command, AC_ACCESS_REGISTER_WRITE) == 0) &&
            get_field(command, AC_ACCESS_REGISTER_TRANSFER)) {
            for (size_t i = 0; i < data_n && data; i++) {
                data[i] = read_dtm(DM_DATA0 + i);
            }
        }
        const auto err = get_field(abstractcs, DM_ABSTRACTCS_CMDERR);
        if (err > 0 && log_error) {
            die(err);
        }
        return err;
    }

    void die(const uint32_t err) {
        const auto msg = (err < std::size(rv_error_codes)) ? rv_error_codes[err] : "OTHER";
        soct::logging::fesvr::error << "Debug Abstract Command Error #" << err << " (" << msg << ")" << "\n";
        write_dtm(DM_ABSTRACTCS, DM_ABSTRACTCS_CMDERR);
    }

    uint64_t save_reg(const uint32_t regno) {
        massert(m_rv_info.has_value(), "System info has not been initialized");
        const auto xlen = m_rv_info.value().xlen;

        uint32_t data[xlen / (8 * 4)];
        uint32_t command = AC_ACCESS_REGISTER_TRANSFER | rv_ac_ar_size(xlen) | rv_ac_ar_regno(regno);
        run_abstract_command(command, nullptr, 0, data, xlen / (8 * 4));

        uint64_t result = data[0];
        if (xlen > 32) {
            result |= ((uint64_t)data[1]) << 32;
        }
        return result;
    }

    void restore_reg(const uint32_t regno, const reg_t val) {
        massert(m_rv_info.has_value(), "System info has not been initialized");
        const auto xlen = m_rv_info.value().xlen;

        uint32_t data[xlen / (8 * 4)];
        data[0] = (uint32_t)val;
        if (xlen > 32) {
            data[1] = (uint32_t)(val >> 32);
        }

        uint32_t command = AC_ACCESS_REGISTER_TRANSFER |
            AC_ACCESS_REGISTER_WRITE |
            rv_ac_ar_size(xlen) |
            rv_ac_ar_regno(regno);

        run_abstract_command(command, nullptr, 0, data, xlen / (8 * 4));
    }

    void fence_i() {
        massert(m_rv_info.has_value(), "System info has not been initialized");

        halt(m_current_hart);
        constexpr uint32_t prog[] = {MATCH_FENCE_I, MATCH_EBREAK};

        const uint32_t command = AC_ACCESS_REGISTER_POSTEXEC |
            AC_ACCESS_REGISTER_TRANSFER |
            AC_ACCESS_REGISTER_WRITE |
            rv_ac_ar_size(m_rv_info.value().xlen) |
            rv_ac_ar_regno(RV_X0);

        run_abstract_command(command, prog, std::size(prog));

        resume(m_current_hart);
    }

    /// Get the XLEN of the system.
    uint32_t get_xlen() {
        constexpr uint32_t command = AC_ACCESS_REGISTER_TRANSFER | rv_ac_ar_regno(RV_S0);
        constexpr uint32_t prog[] = {};
        uint32_t data[] = {};

        uint32_t cmderr = run_abstract_command(command | rv_ac_ar_size(128), prog, 0, data, 0, false);
        if (cmderr == 0) {
            throw std::runtime_error("FESVR DTM does not support 128-bit");
        }
        write_dtm(DM_ABSTRACTCS, DM_ABSTRACTCS_CMDERR);

        cmderr = run_abstract_command(command | rv_ac_ar_size(64), prog, 0, data, 0, false);
        if (cmderr == 0) {
            return 64;
        }
        write_dtm(DM_ABSTRACTCS, DM_ABSTRACTCS_CMDERR);

        cmderr = run_abstract_command(command | rv_ac_ar_size(32), prog, 0, data, 0, false);
        if (cmderr == 0) {
            return 32;
        }

        throw std::runtime_error("FESVR DTM can't determine XLEN. Aborting");
    }

    /* Memory Interface, implemented in .cpp file */
    void read_chunk(uint64_t taddr, size_t len, uint8_t* dst);

    void write_chunk(addr_t taddr, size_t len, const uint8_t* src);

    void clear_chunk(addr_t taddr, size_t len);

    [[nodiscard]] size_t chunk_align() const;

    [[nodiscard]] size_t chunk_max_size() const;

    void read(addr_t addr, size_t len, uint8_t* bytes);

    void write(addr_t addr, size_t len, const uint8_t* bytes);

    void log_rw_progress(const bool enable) {
        m_log_rw_progress = enable;
    }

    [[nodiscard]] std::shared_ptr<chunked_memif_t> init_chunked_memif();

    /// Information about the RISC-V system. Initialized in the thread_main function.
    std::optional<rv_info_t> m_rv_info = std::nullopt;

    /// The host thread.
    std::thread m_host_thread;

    /// The state of the DTM. Used by m_host_thread
    enum state_t {
        WAITING_RESP, WAITING_REQ
    } m_state = WAITING_REQ;

    /// The current hart selected.
    size_t m_current_hart = 0;

    /// A multithreaded queue for requests. The main thread enqueues requests to target here and m_host_thread dequeues them.
    mt_queue_t<req_t> m_req_buf{};

    /// The current request - a staging area for the request. Only used by m_host_thread.
    req_t m_req{};

    /// A multithreaded queue for responses. The main thread dequeues responses from the target here and m_host_thread enqueues them.
    mt_queue_t<resp_t> m_resp_buf{};

    /// Whether the target is ready to accept a request. Set only by m_host_thread.
    bool m_target_ready;

    /// True if the DTM is running.
    bool m_running = false;

    /// Whether to log read/write progress.
    bool m_log_rw_progress = false;

    /// The number of idle cycles.
    const size_t m_idle_cycles = 1000;

    // Members only used for tracing.
    TRACE(uint64_t cycle = 0;)
};
