#pragma once

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h> // Not needed but used in posix compat layer

#include "soct/syscalls.h"

/**
 * The status of a syscall handler, indicating whether the syscall was handled or not. If a handler returns SOCT_HANDLER_HANDLED, the syscall is considered handled and will not be passed on to any further handlers. If it returns SOCT_HANDLER_PASS, the next handler in the chain will be tried.
 */
typedef enum {
    SOCT_HANDLER_PASS, // Not handled, pass to next handler
    SOCT_HANDLER_HANDLED, // Handled, dont pass on to next handler
} soct_handler_status_t;


/**
 * The response of a syscall handler, containing the status and the return value of the syscall. The return value is only valid if the status is SOCT_HANDLER_HANDLED.
 */
typedef struct {
    soct_handler_status_t status;
    sc_resp_t ret;
} soct_handler_resp_t;


/**
 * A handler for a syscall.
 * It takes the response memory, the syscall number and up to 7 arguments, and returns a soct_handler_resp_t indicating whether the syscall was handled or not.
 */
typedef struct {
    void (*handle)(
        soct_handler_resp_t *resp,
        sc_type_t syscall,
        sc_arg_t a0,
        sc_arg_t a1,
        sc_arg_t a2,
        sc_arg_t a3,
        sc_arg_t a4,
        sc_arg_t a5,
        sc_arg_t a6);
} soct_handler_t;


/**
 * Perform a syscall with the given number and arguments. The number of arguments is specified by nargs, and the arguments are passed as variadic parameters.
 * The return value can be checked using check_ptr or check_ret depending on the expected type.
 */
long soct_syscall(
    sc_type_t syscall,
    sc_arg_t a0,
    sc_arg_t a1,
    sc_arg_t a2,
    sc_arg_t a3,
    sc_arg_t a4,
    sc_arg_t a5,
    sc_arg_t a6);


/**
 * Register a user handler with high priority. User handlers are tried before default handlers.
 * @return true on success, false if the handler table is full.
 */
bool soct_register_handler(soct_handler_t handler);


/**
 * Register a default (low-priority) handler. Default handlers are tried only after all user
 * handlers have passed. Intended for use by soctglue internals (UART, SDC, HTIF).
 * @return true on success, false if the handler table is full.
 */
bool soct_register_default_handler(soct_handler_t handler);

