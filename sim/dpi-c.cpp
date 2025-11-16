#include "dpi-c.hpp"
#include "logging.hpp"


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
        globals::dtm = new dtm_t(globals::argc, globals::argv);
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
