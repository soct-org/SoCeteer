#pragma once
#include "common.h"
#include <stddef.h>
#include <stdio.h>
#include <sys/stat.h>

#define MAX_FDS 16

// Write to a file descriptor
ssize_t io_write(int fd, const void* ptr, size_t len);

// Read from a file descriptor
ssize_t io_read(int fd, void* ptr, size_t len);

// Open a file
FILE* io_fopen(const char* path, const char* mode);


int io_open(const char* path, int flags, int mode);

int io_close(int fd);

// Seek to a file descriptor
int io_seek(int fd, off_t offset);


int io_fstat(int fd, struct stat* st);
