#pragma once

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h> // Not needed but used in posix compat layer

#include "soct/syscalls.h"

void soct_add_setup_msg(const char *msg);

typedef enum {
    SOCT_HANDLER_PASS, // Not handled, pass to next handler
    SOCT_HANDLER_HANDLED, // Handled, dont pass on to next handler
} soct_handler_status_t;


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
 * @param handler The handler to register. The handler will be called for every syscall in the order they were registered until one of them handles the syscall, the syscall becomes invalid or there are no handlers left.
 * @return Whether the registration was successful. Registration can fail if the maximum number of handlers has been reached.
 */
bool soct_register_handler(soct_handler_t handler);
