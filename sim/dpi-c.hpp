#pragma once

#include "dtm.hpp"
#include "remote/bitbang.hpp"


namespace soct::globals {
    /// A global dtm_t instance for debug_tick to use.
    inline dtm_t* dtm = nullptr;

    /// A global jtag_t instance for jtag_tick to use.
    inline remote_bitbang_t* jtag = nullptr;

    /// The number of arguments passed to the program.
    inline int argc = 0;
    inline char** argv = nullptr;
}
