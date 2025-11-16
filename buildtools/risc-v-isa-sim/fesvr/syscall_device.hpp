// See LICENSE for license details.
#pragma once

#include <vector>
#include <memory>
#include <functional>
#include "device.hpp"
#include "types.hpp"
#include "logging.hpp"
#include "memif.hpp"

#ifndef _WIN32
#include <fcntl.h>
#endif

/// File descriptor allocator
class fds_t {
public:
    enum fd_type {
        invalid_t = -1,
        stdin_t = 0,
        stdout_t = 1,
        stderr_t = 2,
        any_t = 3
    };

  	/// Allocate a file descriptor
    size_t alloc(sreg_t fd);

    /// Deallocate a file descriptor
    void dealloc(sreg_t fd);

    /// Lookup a file descriptor
    [[nodiscard]] std::pair<sreg_t, fd_type> lookup(size_t fd) const;

    ~fds_t();

private:
  	/// The file descriptors
    std::vector<uint64_t> m_fds;
};

class syscall_device_t final : public device_t {
public:

    syscall_device_t(const std::shared_ptr<htif_t>& htif, const std::shared_ptr<chunked_memif_t>& cmemif);

    ~syscall_device_t() override;

    /// Set the chroot directory for the syscall proxy
    void set_chroot(const char* where);


    std::string_view name() override {
        return "syscall";
    }

private:
    /// The command handler for the syscall device
    void handle_syscall(const cmd_t& cmd);

    /// Helper function to return -errno if the return value is -1
    static int64_t sysret_errno(int64_t ret);

    uint64_t sys_read(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t);

    uint64_t sys_write(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t);

    uint64_t sys_open(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t);

    uint64_t sys_close(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t);

    uint64_t sys_lseek(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t);

    uint64_t sys_exit(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t);

    uint64_t sys_openat(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t);

    /// Custom syscalls:
    uint64_t sys_pathconf(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t);

    uint64_t sys_getmainvars(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t);

    /// Use the index of syscall and pass it to OS:
    uint64_t syscall_any(uint32_t sysno, uint64_t a0, uint64_t a1, uint64_t a2, uint64_t a3, uint64_t a4, uint64_t a5, uint64_t a6);

    /// Maximum number of syscalls
    constexpr static size_t MAX_SYSCALLS = 2048;

    /// Syscall table
    std::vector<syscall_func_t> m_table{MAX_SYSCALLS};

    /// File descriptor allocator
    fds_t m_fds;

    /// Contains the file descriptors from the system
    std::vector<uint64_t> m_fds_system;

};
