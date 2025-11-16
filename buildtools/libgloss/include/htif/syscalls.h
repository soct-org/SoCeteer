#pragma once
/* Frontend system calls supported by fesvr. See also https://filippo.io/linux-syscall-table/*/
#define FESVR_read 0
#define FESVR_write 1
#define FESVR_open 2
#define FESVR_close 3
#define FESVR_stat 4
#define FESVR_fstat 5
#define FESVR_lseek 8
#define FESVR_getpid 39
#define FESVR_exit 60
#define FESVR_kill 62
#define FESVR_getcwd 79
#define FESVR_chdir 80
#define FESVR_mkdir 83
#define FESVR_link 86
#define FESVR_unlink 87
#define FESVR_chmod 90
#define FESVR_gettimeofday 96
#define FESVR_openat 257

/* Custom system call */
#define FESVR_pathconf 2010
#define FESVR_getmainvars 2011
