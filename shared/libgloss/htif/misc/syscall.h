#pragma once

#include "htif/syscalls.h"

#ifndef __ASSEMBLY__

#include <errno.h>
#include <stddef.h>
#include <sys/stat.h>
#include <sys/types.h>
#include "htif.h"

static inline long htif_check_ret(long ret) {
	if (ret < 0) {
		errno = (int)-ret;
		return -1;
	}

	return ret;
}

static inline void *htif_check_ptr(long ret) {
	if (ret < 0) {
		errno = (int)-ret;
		return NULL;
	}

	return (void *)(uintptr_t)ret;
}

#endif /* !__ASSEMBLY__ */