#define _GNU_SOURCE


#include "boardio.h"
#include "uart.h"
#include <string.h>
#include <fcntl.h>
#include <stdio.h>
#include <sys/errno.h>

#include "ff.h"

typedef struct {
    FIL fil;
    int fd;
    int in_use;
} FD_ENTRY;

static FD_ENTRY fd_table[MAX_FDS] = {
    { .fd = 0, .in_use = 1 }, // stdin
    { .fd = 1, .in_use = 1 }, // stdout
    { .fd = 2, .in_use = 1 }  // stderr
};

static FATFS fs; // File system object


extern const char * errno_to_str(void);

void __init_disk(const uint32_t hart) {
    if (hart != 0) {
        return; // Only initialize disk for hart 0
    }
    const FRESULT res = f_mount(&fs, "", 1);
    if (res != FR_OK) {
        printf("Failed to mount filesystem: %d\n", res);
    }
    printf("Successfully mounted filesystem\n");
}

// Find a free FD
static FD_ENTRY* alloc_fd() {
    for (int i = 3; i < MAX_FDS; i++) { // Skip stdin = 0, stdout = 1, stderr = 2
        if (!fd_table[i].in_use) {
            fd_table[i].in_use = 1;
            fd_table[i].fd = i;
            return &fd_table[i];
        }
    }
    return NULL;
}

// Free an FD
static void free_fd(const int fd) {
    if (fd >= 0 && fd < MAX_FDS) {
        fd_table[fd].in_use = 0;
        fd_table[fd].fd = -1;
    }
}

// Convert POSIX mode string to FatFs flags
BYTE mode_to_flags(const char* mode) {
    BYTE flags = 0;
    if (strchr(mode, 'r')) {
        if (strchr(mode, '+')) {
            flags = FA_READ | FA_WRITE | FA_OPEN_ALWAYS ; // Read and write, fail if not exists
        } else {
            flags = FA_READ | FA_OPEN_ALWAYS; // Read only, fail if not exists
        }
    }
    if (strchr(mode, 'w')) {
        if (strchr(mode, '+')) {
            flags = FA_READ | FA_WRITE | FA_CREATE_NEW | FA_CREATE_ALWAYS; // Read and write, create new file
        } else {
            flags = FA_WRITE; // Write only, create new file
        }
    }
    if (strchr(mode, 'a')) {
        if (strchr(mode, '+')) {
            flags = FA_READ | FA_WRITE | FA_OPEN_APPEND; // Read and write, append to file
        } else {
            flags = FA_WRITE | FA_OPEN_APPEND; // Write only, append to file
        }
    }
    return flags;
}

FD_ENTRY* _ff_open(const char* path, const int flags) {
    FD_ENTRY* entry = alloc_fd();
    if (entry == NULL) {
        errno = ENOMEM;
        return NULL;
    }
    const int fd = entry->fd;
    const FRESULT res = f_open(&entry->fil, path, flags);
    if (res != FR_OK) {
        printf("Failed to open file '%s': %s\n", path, errno_to_str());
        free_fd(fd);
        return NULL;
    }
    return entry;
}

int _ff_close(FD_ENTRY* entry) {
    if (entry == NULL) {
        return 0; // Nothing to close
    }
    if (entry->fd == 0 || entry->fd == 1 || entry->fd == 2) {
        // stdin, stdout, stderr are not closed
        return 0;
    }
    // Check if the entry is valid
    if (entry->fd < 0 || entry->fd >= MAX_FDS || !entry->in_use)
        return -1;

    f_close(&entry->fil);
    free_fd(entry->fd);
    return 0;
}

int _ff_read(FD_ENTRY* entry, void* buf, const uint32_t count) {
    if (!entry->in_use) {
        errno = EBADF; // Bad file descriptor
        return -1;
    }
    UINT br;
    const FRESULT res = f_read(&entry->fil, buf, count, &br);
    if (res != FR_OK) {
        errno = EIO; // I/O error
        return -1;
    }
    return (int)br; // Number of bytes read
}

int _ff_write(FD_ENTRY* entry, const void* buf, const uint32_t count) {
    if (!entry->in_use) {
        errno = EBADF; // Bad file descriptor
        return -1;
    }
    UINT bw;
    const FRESULT res = f_write(&entry->fil, buf, count, &bw);
    if (res != FR_OK) {
        errno = EIO; // I/O error
        return -1;
    }
    return (int)bw; // Number of bytes written
}

int _ff_lseek(FD_ENTRY* entry, const off_t offset) {
    if (!entry->in_use) {
        errno = EBADF; // Bad file descriptor
        return -1;
    }

    const FRESULT res = f_lseek(&entry->fil, offset);
    if (res != FR_OK) {
        errno = EIO; // I/O error
        return -1;
    }
    return 0; // Success
}


// Adapter for io_read
ssize_t cookie_io_read(void* cookie, char* buf, const size_t size) {
    FD_ENTRY* entry = cookie;
    return _ff_read(entry, buf, size);
}

// Adapter for io_write
ssize_t cookie_io_write(void* cookie, const char* buf, size_t size) {
    FD_ENTRY* entry = cookie;
    return _ff_write(entry, buf, size);
}

// Adapter for io_seek
int cookie_io_seek(void* cookie, off_t* offset, int whence) {
    FD_ENTRY* entry = cookie;
    return _ff_lseek(entry, *offset);
}

// Adapter for io_close
int cookie_io_close(void* cookie) {
    FD_ENTRY* entry = cookie;
    return _ff_close(entry);
}


cookie_io_functions_t cookie_io_functions = {
    .read = cookie_io_read,
    .write = cookie_io_write,
    .seek = cookie_io_seek, // Ensure io_seek matches the expected signature
    .close = cookie_io_close // Ensure io_close matches the expected signature
};


//--------------------- Public API ---------------------//

// Write to a file descriptor
ssize_t io_write(const int fd, const void* ptr, const size_t len) {
    if (fd == 1) { // stdout
        kputs(ptr, len);
        return (ssize_t)len;
    }
    if (fd == 2) { // stderr
        kputs(ptr, len);
        return (ssize_t)len;
    }
    if (fd < 0 || fd >= MAX_FDS || !fd_table[fd].in_use) {
        errno = EBADF; // Bad file descriptor
        return -1;
    }
    return _ff_write(&fd_table[fd], ptr, len);
}

// Read from a file descriptor
ssize_t io_read(const int fd, void* ptr, const size_t len) {
    if (fd == 0) { // stdin
        char* buf = ptr;
        for (size_t i = 0; i < len; i++) {
            buf[i] = kgetc();
        }
        return (ssize_t)len;
    }
    if (fd < 0 || fd >= MAX_FDS || !fd_table[fd].in_use) {
        errno = EBADF; // Bad file descriptor
        return -1;
    }
    return _ff_read(&fd_table[fd], ptr, len);
}

// Open a file
FILE* io_fopen(const char* path, const char* mode) {
    FD_ENTRY* entry = _ff_open(path, mode_to_flags(mode));
    if (entry == NULL) {
        return NULL;
    }
    FILE* fp = fopencookie(entry, mode, cookie_io_functions);

    if (fp == NULL) {
        _ff_close(entry);
        errno = ENOMEM; // Not enough memory
        return NULL;
    }
    return fp;
}

int io_close(const int fd) {
    if (fd < 0 || fd >= MAX_FDS || !fd_table[fd].in_use) {
        errno = EBADF; // Bad file descriptor
        return -1;
    }
    return _ff_close(&fd_table[fd]);
}

// Seek to a file descriptor
int io_seek(const int fd, const off_t offset) {
    if (fd < 0 || fd >= MAX_FDS || !fd_table[fd].in_use) {
        errno = EBADF; // Bad file descriptor
        return -1;
    }
    return _ff_lseek(&fd_table[fd], offset);
}

int io_fstat(int fd, struct stat* st) {
    if (fd < 0 || fd >= MAX_FDS || !fd_table[fd].in_use)
        return -1;

    // Fill the stat structure
    st->st_mode = S_IFREG; // Regular file
    st->st_size = f_size(&fd_table[fd].fil);
    st->st_atime = 0; // Not supported
    st->st_mtime = 0; // Not supported
    st->st_ctime = 0; // Not supported

    return 0;
}
