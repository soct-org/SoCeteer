#pragma once
/*
 * Frontend system calls supported by SOCT. They equal the linux syscall numbers.
 * See also https://filippo.io/linux-syscall-table/
 */
#define SOCT_READ 0
#define SOCT_WRITE 1
#define SOCT_OPEN 2
#define SOCT_CLOSE 3
#define SOCT_STAT 4
#define SOCT_FSTAT 5
#define SOCT_LSEEK 8
#define SOCT_EXIT 60
#define SOCT_OPENAT 257

// Custom Syscalls - Not posix
#define SOCT_PATHCONF 2010
#define SOCT_GET_MAINVARS 2011
#define SOCT_HTIF_DEV_TEST 2012 // Test whether the HTIF device is present by sending a command and waiting for a response

