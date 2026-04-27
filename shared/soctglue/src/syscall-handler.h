#pragma once

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include "soct/syscalls.h"


typedef enum {
    SOCT_HANDLER_PASS, // Not handled, pass to next handler
    SOCT_HANDLER_HANDLED, // Handled, dont pass on to next handler
} soct_handler_status_t;


typedef struct {
    soct_handler_status_t status;
    long ret;
} soct_handler_resp_t;


/**
 * A handler for a syscall.
 * It takes the response memory, the syscall number and up to 7 arguments, and returns a soct_handler_resp_t indicating whether the syscall was handled or not.
 */
typedef struct {
    void (*handle)(
        soct_handler_resp_t *resp,
        uint32_t syscall,
        uint64_t arg0,
        uint64_t arg1,
        uint64_t arg2,
        uint64_t arg3,
        uint64_t arg4,
        uint64_t arg5,
        uint64_t arg6);
} soct_handler_t;


/**
 * @param handler The handler to register. The handler will be called for every syscall in the order they were registered until one of them handles the syscall, the syscall becomes invalid or there are no handlers left.
 * @return Whether the registration was successful. Registration can fail if the maximum number of handlers has been reached.
 */
bool soct_register_handler(soct_handler_t handler);


/**
 * Perform a syscall with the given number and arguments. The number of arguments is specified by nargs, and the arguments are passed as variadic parameters.
 * The return value can be checked using check_ptr or check_ret depending on the expected type.
 */
long soct_syscall(
    uint32_t syscall,
    uint64_t arg0,
    uint64_t arg1,
    uint64_t arg2,
    uint64_t arg3,
    uint64_t arg4,
    uint64_t arg5,
    uint64_t arg6);
