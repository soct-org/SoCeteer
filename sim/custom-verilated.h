#pragma once
#include "logging.hpp"

#ifndef FWRITE_NO_CUSTOM
    #define fwrite fwrite_
#endif

#ifndef VL_PRINT_NO_CUSTOM
    #define VL_PRINTF vl_printf
#endif

#define VL_USER_STOP_MAYBE  // override the vl_stop_maybe function as it dumps too much information to the console
inline void vl_stop_maybe(const char* filename, int linenum, const char* hier, bool maybe) {
    if (__builtin_expect(soct::logging::globals::all2console, 0))
        soct::logging::circuit::console << "Verilator stop at " << filename << ":" << linenum << " in " << hier << std::endl;
}

inline void vl_printf(const char* formatp, ...) {
    if (__builtin_expect(soct::logging::globals::all2console, 0)) {
        va_list args;
        va_start(args, formatp);
        vprintf(formatp, args);
        va_end(args);
    }
}


/// Custom fwrite function to log any message emitted via $display function (or printf in Chisel).
inline int fwrite_(const char* addr, int, const size_t len, ...) {
    using namespace soct;
    const std::string_view circuit_msg{addr, len};
    const bool prefix_within_log_level = logging::prefix_within_log_level(circuit_msg);
    // if the message starts with [<log_level>] and the log level is below the current log level or all2console is enabled, log it
    if (__builtin_expect(prefix_within_log_level || logging::globals::all2console, 0)) {
        logging::circuit::console << circuit_msg;
    }

    // if the message is from a RocketChip core, log the disassembly
    if (logging::is_rocketchip_log(circuit_msg)) {
        if (!logging::globals::all2console && !logging::is_log_file_enabled()){
            return 0;
        }
        const std::string dasm {logging::get_rocket_chip_dasm(circuit_msg)};
        const uint64_t bits = std::stoull(dasm, nullptr, 16);
        const auto insn = logging::globals::disasm.disassemble(bits);
        if (logging::is_log_file_enabled()) {
            logging::rocketchip::file << circuit_msg << insn << "\n"; // circuit_msg comes with newline
        }
        if (logging::globals::all2console) {
            logging::circuit::console << insn << "\n"; // we already printed the circuit_msg
        }
    } else if (logging::is_log_file_enabled()) {
        logging::circuit::file << circuit_msg << "\n";
    }
    return 0;
}