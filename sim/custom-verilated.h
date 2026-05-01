#pragma once
#include "logging.hpp"

#ifndef FWRITE_NO_CUSTOM
    #define fwrite fwrite_
#endif

#ifndef VL_PRINT_NO_CUSTOM
    #define VL_PRINTF vl_printf
#endif

#define VL_USER_STOP_MAYBE  // override the vl_stop_maybe function as it dumps too much information to the console
void vl_stop_maybe(const char* filename, int linenum, const char* hier, bool maybe);

void vl_printf(const char* formatp, ...);

/// Custom fwrite function to log any message emitted via $display function (or printf in Chisel).
int fwrite_(const char* addr, int, const size_t len, ...);
