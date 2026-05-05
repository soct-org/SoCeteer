#pragma once
#include "stddef.h"

/**
 * Get the number of harts or -1 if it cannot be determined
 */
int soct_n_harts(void);


/**
 * Get the current hart ID.
 */
size_t soct_hart_id(void);


/** Set the current hart to sleep until an interrupt is received. */
#define wfi() __asm__ __volatile__ ("wfi")
