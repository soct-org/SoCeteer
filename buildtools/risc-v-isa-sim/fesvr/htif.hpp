#pragma once

#include <platform.h>
#include <queue>
#include <utility>

#include "memif.hpp"
#include "elfloader.hpp"
#include "logging.hpp"
#include "device.hpp"
#include "syscall_device.hpp"

class command_handler_t;

class htif_t {
public:
    virtual ~htif_t() = default;

    htif_t(const int argc, char** argv, std::shared_ptr<chunked_memif_t> cmemif) :
    m_cmemif(std::move(cmemif)),
    m_syscall_proxy(std::shared_ptr<htif_t>(this), m_cmemif) {
        parse_arguments(argc, argv);
        register_devices();
    }

    int32_t run() {
        start();
        // Contains the data to be sent to the target
        std::queue<reg_t> fromhost_queue;
        const auto enqueue = [&fromhost_queue](const reg_t x) {
            soct::logging::fesvr::debug << "Enqueueing " << x << '\n';
            fromhost_queue.push(x);
        };
        while (!stopped()) {
            const auto to_host = m_cmemif->read_int<reg_t>(m_tohost_addr);
            if (to_host != 0) {
                m_cmemif->write_int<reg_t>(m_tohost_addr, 0); // clear tohost
                m_device_list.handle_command({to_host, enqueue});
                m_device_list.tick();
            } else {
                idle();
            }
            // write the next block to the target if it's ready (from_host is zero)
            const auto from_host = m_cmemif->read_int<reg_t>(m_fromhost_addr);
            if (!fromhost_queue.empty() && from_host == 0) {
                m_cmemif->write_int<reg_t>(m_fromhost_addr, fromhost_queue.front());
                fromhost_queue.pop();
            }
        }
        // TODO evaluate signature
        return m_exitcode.value();
    }

    [[nodiscard]] bool stopped() const {
        return m_exitcode.has_value();
    }

    [[nodiscard]] reg_t entry() const {
        return m_entry;
    }

    [[nodiscard]] std::vector<std::string> target_args() const {
        return m_targs;
    }

    /// Set the exit code of the target
    void set_exitcode(int32_t exitcode) {
        m_exitcode = exitcode;
    }

    /// Get the exit code of the target. Target must be stopped.
    [[nodiscard]] int32_t exitcode() const {
        if (!m_exitcode.has_value()) {
            soct::logging::fesvr::warn << "Target is not stopped, exit code is not available\n";
            return -1;
        }
        return m_exitcode.value();
    }

protected:
    // Implemented in the derived class
    virtual void reset() = 0;

    virtual void idle() = 0;

    // A pointer to the memory interface
    std::shared_ptr<chunked_memif_t> m_cmemif;

private:
    // Register the devices with the device list.
    void register_devices() {
        soct::logging::fesvr::debug << "Registering devices\n";
        m_device_list.register_device(&m_syscall_proxy);
    }

    void start() {
        if (m_path_to_elf.empty()) {
            throw std::runtime_error("no payload specified");
        }
        if (!std::filesystem::exists(m_path_to_elf)) {
            throw std::runtime_error("could find payload " + m_path_to_elf);
        }
        if (std::filesystem::is_directory(m_path_to_elf)) {
            throw std::runtime_error("payload " + m_path_to_elf + " is a directory");
        }
        m_cmemif->log_rw_progress(true);
        const std::map<std::string, uint64_t> symbols = load_elf(m_path_to_elf, m_cmemif, &m_entry, m_load_offset);
        m_cmemif->log_rw_progress(false);
        if (symbols.contains("tohost") && symbols.contains("fromhost")) {
            m_tohost_addr = symbols.at("tohost");
            m_fromhost_addr = symbols.at("fromhost");
            soct::logging::fesvr::debug << "tohost address: " << m_tohost_addr <<
                ", fromhost address: " << m_fromhost_addr << '\n';
        } else {
            soct::logging::fesvr::warn << "tohost and fromhost symbols not in ELF; using fallback addresses: tohost address: 2147498496, fromhost address: 2147498504\n";
            m_tohost_addr = 2147498496;
            m_fromhost_addr = 2147498504;
        }
        if (symbols.contains("begin_signature") && symbols.contains("end_signature")) {
            soct::logging::fesvr::debug << "Torture test detected\n";
            m_torture_sig_addr = symbols.at("begin_signature");
            m_torture_sig_len = symbols.at("end_signature") - m_torture_sig_addr.value();
        }
        reset();
    }

    /// The exit code of the target
    std::optional<int32_t> m_exitcode;

    ///@brief Contains the arguments to be passed to the target. First argument is always the target binary
    std::vector<std::string> m_targs;

    ///@brief Contains the .elf file to be loaded
    std::string m_path_to_elf;

    /// The address of the entry point of the target
    reg_t m_entry = DRAM_BASE;

    /// The offset at which the target binary should be loaded
    reg_t m_load_offset = DRAM_BASE;

    /// The address of the tohost symbol which is used to send data to the host
    reg_t m_tohost_addr = 0;

    /// The address of the fromhost symbol which is used to receive data from the host
    reg_t m_fromhost_addr = 0;

    /// The address of the begin_signature symbol which is used to detect torture tests
    std::optional<reg_t> m_torture_sig_addr = std::nullopt;

    /// The length of the signature
    std::optional<reg_t> m_torture_sig_len = std::nullopt;

    /// Contains the devices
    device_list_t m_device_list;

    /// The syscall proxy device
    syscall_device_t m_syscall_proxy;

    /// Prints the usage of the program
    static void usage(const std::string_view program_name) {
        soct::logging::fesvr::info << "Usage: " << program_name <<
            " <path to elf> [--tgt=\"<arg1>\" --tgt=\"<arg2>\" ...]\n";
    }

    /// Parses the arguments passed to the program and stores them in the m_targs vector
    void parse_arguments(const int argc, char* argv[]) {
        using namespace std::literals::string_view_literals;
        using namespace soct;

        std::vector<std::string_view> args(argv + 1, argv + argc);

        const std::string program_name = argv[0];

        if (std::ranges::any_of(args, [](const std::string_view arg) { return arg == "-h" || arg == "--help"; })) {
            usage(program_name);
            exit(0);
        }

        // Parse target arguments of the form --tgt="arg1" --tgt="arg2" ...
        constexpr auto tgt_key = "--tgt="sv;
        for (auto arg : args) {
            if (arg.starts_with(tgt_key)) {
                m_targs.emplace_back(arg.substr(tgt_key.size()));
                args.erase(std::ranges::find(args, arg));
            } else {
                m_path_to_elf = arg;
            }
        }

        // insert at 0 to ensure that the main payload is the first element
        m_targs.insert(m_targs.begin(), m_path_to_elf);

        logging::fesvr::info << "Arguments passed to target: ";
        for (const auto& arg : m_targs)
            logging::fesvr::info << arg << ", ";
        logging::fesvr::info << '\n';
    }
};
