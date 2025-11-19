#include "syscall.h"

int _open(const char* name, int flags, int mode) {
	return htif_syscall_3(FESVR_open, (uintptr_t) name, flags, mode);
}
