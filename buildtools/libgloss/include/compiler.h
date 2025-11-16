#pragma once

#ifndef __ASSEMBLY__

#define likely(x)   __builtin_expect(!!(x), 1)
#define unlikely(x) __builtin_expect(!!(x), 0)

#endif /* __ASSEMBLY__*/