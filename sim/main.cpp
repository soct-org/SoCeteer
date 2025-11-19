#define STRINGIFY(x) #x
#define TOSTRING(x) STRINGIFY(x)

#include <fstream>
#include <utility>
#include "verilated.h"
#include "logging.hpp"
#include "dpi-c.hpp"
#include "timepp.hpp"

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

using SystemType = std::conditional_t<use_vl_prefix, VL_PREFIX, SystemTop>;

#ifdef VL_TRACE
void tic_toc(const std::unique_ptr<SystemTop>& topp,
             const std::unique_ptr<VerilatedContext>& contextp,
             const std::unique_ptr<VerilatedVcdC>& tfp) {
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
void tic_toc(const std::unique_ptr<SystemType>& topp,
             const std::unique_ptr<VerilatedContext>& contextp) {
    topp->clock = 0;
    topp->eval();
    topp->clock = 1;
    topp->eval();
    contextp->timeInc(1);
}

#endif

int main(const int argc, char* argv[]) {
    using namespace soct;
    Verilated::debug(0);

    // Make argv and argc available to the debug_tick function:
    globals::argc = argc;
    globals::argv = argv;

    // Repository-specific configuration:
    logging::globals::all2console = false; // Disable dumping all output to the console
    logging::init_logging("log.txt"s); // Initialize logging to a file
    logging::globals::log_cores.emplace_back("0"sv); // Log core 0
    logging::globals::log_level = logging::LogLevel::DEBUG;

    const std::unique_ptr<VerilatedContext> contextp{new VerilatedContext};

    contextp->commandArgs(argc, argv);
    const std::unique_ptr<SystemType> topp{new SystemType{contextp.get()}};

    timepush("Simulation loop");
#ifdef VL_TRACE
    // Initialize VCD tracing
    const std::string vcd_filename = "dump.vcd";
    logging::fesvr::info << "Initializing VCD tracing with depth " << VL_TRACE_DEPTH << " to " << vcd_filename << "\n";
    Verilated::traceEverOn(true);
    const std::unique_ptr<VerilatedVcdC> tfp{new VerilatedVcdC};
    topp->trace(tfp.get(), VL_TRACE_DEPTH);
    tfp->open(vcd_filename.c_str());
    auto tic_toc_impl = [&] {
        tic_toc(topp, contextp, tfp);
    };
#else
    auto tic_toc_impl = [&] {
        tic_toc(topp, contextp);
    };
#endif

    topp->reset = 1;
    for (int i = 0; i < 100; i++) {
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
