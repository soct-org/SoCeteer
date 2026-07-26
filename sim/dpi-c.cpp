#include "dpi-c.hpp"
#include "logging.hpp"

// The target's address map, from the generated C mirror of its device tree (the
// simulator wraps a specific design, so it reads the same soct-dts.h that design's
// software builds against). The fesvr itself stays target-agnostic - this is the one
// place target addresses enter it.
#include <soct-dts.h>
static target_map_t soct_target_map() {
    target_map_t map{};
    map.dram_base = SOCT_DTS_MEMORY_BASE;
#ifdef SOCT_DTS_CLINT_BASE
    map.clint_base = SOCT_DTS_CLINT_BASE;
#endif
    // Fallback HTIF slots for ELFs that export no tohost/fromhost symbols: where
    // soctglue's link places the .htif section, relative to the memory base.
    map.default_tohost = SOCT_DTS_MEMORY_BASE + 0x3a00;
    map.default_fromhost = SOCT_DTS_MEMORY_BASE + 0x3a08;
    return map;
}



extern "C" int debug_tick(
    uint8_t* host2target_valid,
    const uint8_t target_ready,
    uint32_t* host2target_addr,
    uint32_t* host2target_op,
    uint32_t* host2target_data,
    const uint8_t target_resp_valid,
    uint8_t* host2target_resp_ready,
    const uint32_t target2host_resp,
    const uint32_t target2host_data
) {
    using namespace soct;
    if (globals::dtm == nullptr) {
        if (globals::argc == 0 || globals::argv == nullptr) {
            logging::fesvr::error << "Error: argc and argv must be set before calling debug_tick" << std::endl;
            exit(1);
        }
        globals::dtm = new dtm_t(globals::argc, globals::argv, soct_target_map());
    }

    globals::dtm->tick(target_ready, target_resp_valid, {target2host_resp, target2host_data});

    *host2target_resp_ready = globals::dtm->resp_ready(); // We dont send responses
    *host2target_valid = globals::dtm->req_valid();
    *host2target_addr = static_cast<int>(globals::dtm->req_bits().addr);
    *host2target_op = static_cast<int>(globals::dtm->req_bits().op);
    *host2target_data = static_cast<int>(globals::dtm->req_bits().data);


    return globals::dtm->stopped() ? 1 : 0;
}

extern "C" int jtag_tick
(
    unsigned char* jtag_TCK,
    unsigned char* jtag_TMS,
    unsigned char* jtag_TDI,
    unsigned char* jtag_TRSTn,
    const unsigned char jtag_TDO
) {
    using namespace soct;

    if (globals::jtag == nullptr) {
        globals::jtag = new remote_bitbang_t(1337);
    }

    globals::jtag->tick(jtag_TCK, jtag_TMS, jtag_TDI, jtag_TRSTn, jtag_TDO);

    return globals::jtag->done() ? (globals::jtag->exit_code() << 1 | 1) : 0;
}
