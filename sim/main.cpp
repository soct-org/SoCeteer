#define STRINGIFY(x) #x
#define TOSTRING(x) STRINGIFY(x)

#include <disasm.h>
#include <fstream>
#include <memory>
#include "verilated.h"
#include "logging.hpp"
#include "dpi-c.hpp"
#include "timepp.hpp"
#include "argparse.hpp"

#ifdef VL_TRACE
#include "verilated_vcd_c.h"
#endif

#ifdef VL_PREFIX
#include TOSTRING(VL_PREFIX.h)
constexpr bool use_vl_prefix = true;
#else
#include "SystemTop.h"
constexpr bool use_vl_prefix = false;
#endif

using system_t = std::conditional_t<use_vl_prefix, VL_PREFIX, SystemTop>;

isa_parser_t s_isa_parser{SOCT_ARCH, DEFAULT_PRIV};
disassembler_t s_disasm{&s_isa_parser};

extern "C" {
const char *disassemble(uint64_t bits) {
    static thread_local std::string dasm_str;
    dasm_str = s_disasm.disassemble(bits);
    return dasm_str.c_str();
}
}

#ifdef VL_TRACE
void tic_toc(system_t *topp,
             VerilatedContext *contextp,
             VerilatedVcdC *tfp) {
    topp->clock = 0;
    topp->eval();
    tfp->dump(contextp->time());
    contextp->timeInc(1);
    topp->clock = 1;
    topp->eval();
    tfp->dump(contextp->time());
    contextp->timeInc(1);
}
#else
void tic_toc(system_t *topp,
             VerilatedContext *contextp) {
    topp->clock = 0;
    topp->eval();
    topp->clock = 1;
    topp->eval();
    contextp->timeInc(1);
}

#endif

int main(const int argc, char *argv[]) {
    using namespace soct;
    Verilated::debug(0);

    // Initialize global argument parser first — everything else reads from it
    globals::args.parse(argc, argv);
    globals::argc = argc;
    globals::argv = argv;

    // --- Logging configuration (from args) ---
    // All options are optional; logging to file is disabled unless --log-file is given.
    // Run with --help to see all available options.
    logging::globals::all2console = globals::args.has_flag("all2console");

    if (const auto log_file = globals::args.get_value("log-file")) {
        logging::init_logging(*log_file);
    }

    const std::string log_level_str = globals::args.get_value("log-level").value_or("info");
    logging::globals::log_level = logging::parse_log_level(log_level_str);


    // --- Simulation configuration (from args, with sane defaults) ---
    // --reset-cycles=<n>     : number of reset cycles (default: 100)
    const int reset_cycles = [&] {
        const auto v = globals::args.get_value("reset-cycles");
        return v ? std::stoi(*v) : 100;
    }();

    const auto contextp = std::make_unique<VerilatedContext>();
    contextp->commandArgs(argc, argv);
    const auto topp = std::make_unique<system_t>(contextp.get());

    timepush("Total simulation time");
#ifdef VL_TRACE
    // --vcd-file=<path>      : VCD output file path (default: dump.vcd)
    const std::string vcd_file = globals::args.get_value("vcd-file").value_or("dump.vcd");
    logging::fesvr::info << "Initializing VCD tracing with depth " << VL_TRACE_DEPTH << " to " << vcd_file << "\n";
    Verilated::traceEverOn(true);
    const auto tfp = std::make_unique<VerilatedVcdC>();
    topp->trace(tfp.get(), VL_TRACE_DEPTH);
    tfp->open(vcd_file.c_str());
    auto tic_toc_impl = [&] {
        tic_toc(topp.get(), contextp.get(), tfp.get());
    };
#else
    auto tic_toc_impl = [&] {
        tic_toc(topp.get(), contextp.get());
    };
#endif

    topp->reset = 1;
    for (int i = 0; i < reset_cycles; i++) {
        tic_toc_impl();
    }
    topp->reset = 0;

    do {
        timepush("Cycle loop");
        tic_toc_impl();
        timepop();
    } while (!contextp->gotFinish());

    logging::fesvr::info << "Simulation complete with exit code " << globals::dtm->exitcode() << "\n";
    timepop();

    topp->final();
    contextp->statsPrintSummary();
    logging::close_logging();
#ifdef VL_TRACE
    tfp->close();
#endif
}
