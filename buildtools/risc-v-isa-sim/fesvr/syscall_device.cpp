#include "syscall_device.hpp"
#include "htif.hpp"
#include "syscalls.h"

#include <stdexcept>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>

size_t fds_t::alloc(const sreg_t fd) {
    uint64_t i;
    for (i = 0; i < m_fds.size(); i++)
        if (m_fds[i] == -1) // a deallocated fd is available
            break;

    if (i == m_fds.size())
        m_fds.resize(i + 1);

    m_fds[i] = fd;
    return i;
}

void fds_t::dealloc(const sreg_t fd) {
    m_fds[fd] = -1;
}

std::pair<sreg_t, fds_t::fd_type> fds_t::lookup(const size_t fd) const {
    if (static_cast<int>(fd) == RV_AT_FDCWD)
        return {RV_AT_FDCWD, any_t};

    // check if requested fd is i/o fd
    if (fd >= m_fds.size()) {
        return {-1, invalid_t};
    }
    // Here we compare against the integers and not macros as they come from the target and are specific to the software running on it
    // We just have to assume that these requests are valid
    if (fd == 0) {
        return {m_fds[0], stdin_t};
    }
    if (fd == 1) {
        return {m_fds[1], stdout_t};
    }
    if (fd == 2) {
        return {m_fds[2], stderr_t};
    }

    return {m_fds[fd], any_t};
}

fds_t::~fds_t() {
    for (const auto i : m_fds) {
        dealloc(i);
    }
    m_fds.clear();
}

syscall_device_t::~syscall_device_t() {
    // close all system file descriptors
    for (const auto i : m_fds_system) {
        close(m_fds.lookup(i).first);
        m_fds.dealloc(i);
    }
}

syscall_device_t::syscall_device_t(const std::shared_ptr<htif_t>& htif, const std::shared_ptr<chunked_memif_t>& cmemif)
    : device_t(htif, cmemif) {
    using namespace std::literals;
    m_table[FESVR_read] = [this](auto... args) { return sys_read(args...); };
    m_table[FESVR_write] = [this](auto... args) { return sys_write(args...); };
    m_table[FESVR_open] = [this](auto... args) { return sys_open(args...); };
    m_table[FESVR_close] = [this](auto... args) { return sys_close(args...); };
    m_table[FESVR_lseek] = [this](auto... args) { return sys_lseek(args...); };
    m_table[FESVR_exit] = [this](auto... args) { return sys_exit(args...); };
    m_table[FESVR_openat] = [this](auto... args) { return sys_openat(args...); };
    m_table[FESVR_pathconf] = [this](auto... args) { return sys_pathconf(args...); };
    m_table[FESVR_getmainvars] = [this](auto... args) { return sys_getmainvars(args...); };

    register_command(0, [this](const cmd_t& command) { handle_syscall(command); }, "syscall"sv);

    const int stdin_fd = dup(STDIN_FILENO);
    const int stdout_fd = dup(STDOUT_FILENO);
    const int stderr_fd = dup(STDERR_FILENO);
    if (stdin_fd < 0 || stdout_fd < 0 || stderr_fd < 0)
        throw std::runtime_error("could not dup stdin/stdout");

    // fds[0] = stdin, fds[1] = stdout, fds[2] = stderr (due to order of allocation)
    m_fds_system.push_back(m_fds.alloc(stdin_fd));
    m_fds_system.push_back(m_fds.alloc(stdout_fd));
    m_fds_system.push_back(m_fds.alloc(stderr_fd));
}

void syscall_device_t::handle_syscall(const cmd_t& cmd) {
    std::array<uint64_t, 8> htif_mem{};
    const auto mm = cmd.payload();
    m_cmemif->read(mm, sizeof(htif_mem), reinterpret_cast<uint8_t*>(htif_mem.data()));

    const uint32_t syscall = htif_mem[0];
    const uint64_t a0 = htif_mem[1];
    const uint64_t a1 = htif_mem[2];
    const uint64_t a2 = htif_mem[3];
    const uint64_t a3 = htif_mem[4];
    const uint64_t a4 = htif_mem[5];
    const uint64_t a5 = htif_mem[6];
    const uint64_t a6 = htif_mem[7];

    if (syscall >= m_table.size() || !m_table[syscall]) {
#ifdef PASS_UNKNOWN_SYSCALLS
        htif_mem[0] = syscall_any(syscall, a0, a1, a2, a3, a4, a5, a6);
#else
        throw std::runtime_error("Unimplemented syscall: " + std::to_string(syscall));
#endif
    } else {
        htif_mem[0] = m_table[syscall](a0, a1, a2, a3, a4, a5, a6);
    }
    m_cmemif->write(mm, sizeof(htif_mem), reinterpret_cast<uint8_t*>(htif_mem.data()));
    soct::logging::fesvr::debug << "Syscall " << syscall << " handled\n";
    cmd.respond(1);
}

int64_t syscall_device_t::sysret_errno(int64_t ret) {
    return ret == -1 ? -errno : ret;
}

uint64_t syscall_device_t::sys_read(const uint64_t fd, const uint64_t pbuf, const uint64_t len, uint64_t, uint64_t, uint64_t, uint64_t) {
    soct::logging::fesvr::debug << "Reading " << len << " bytes from fd " << fd << " to " << pbuf << "\n";
    std::vector<char> buf(len);
    const ssize_t ret = sysret_errno(read(m_fds.lookup(fd).first, buf.data(), len));
    if (ret >= 0)
        m_cmemif->write(pbuf, ret, reinterpret_cast<uint8_t*>(buf.data()));
    return ret;
}

uint64_t syscall_device_t::sys_write(const uint64_t fd, const uint64_t pbuf, const uint64_t len, uint64_t, uint64_t, uint64_t, uint64_t) {
    soct::logging::fesvr::debug << "Writing " << len << " bytes to fd " << fd << " from " << pbuf << "\n";
    std::vector<char> buf(len);
    m_cmemif->read(pbuf, len, reinterpret_cast<uint8_t*>(buf.data()));

    // here we have to check where the fd is pointing to, so we can direct it to the correct stream
    const auto [fd_, type] = m_fds.lookup(fd);
    if (type == fds_t::stdout_t) {
        soct::logging::elf::to_stdout << std::string(buf.data(), len);
        // Return number of bytes written
        return len;
    }
    if (type == fds_t::stderr_t) {
        soct::logging::elf::to_stderr << std::string(buf.data(), len);
        // Return number of bytes written
        return len;
    }
    // Otherwise, write to the file descriptor
    return sysret_errno(write(fd_, buf.data(), len));
}

uint64_t syscall_device_t::sys_open(const uint64_t pname, const uint64_t flags, const uint64_t mode, uint64_t, uint64_t, uint64_t, uint64_t) {
    auto host_flags = flags;
    auto host_mode = mode;
    // fopen sends flags=0 and mode=438
    if (flags == 0 && mode == 438) {
        soct::logging::fesvr::debug << "Received fopen syscall, applying O_RDONLY flag\n";
        host_flags = O_RDONLY;
        host_mode = 0;
    }
#ifdef _WIN32
    throw std::runtime_error("open not implemented on Windows");
#else
    std::vector<char> name(MAX_PATH_SIZE);
    m_cmemif->read(pname, MAX_PATH_SIZE, reinterpret_cast<uint8_t*>(name.data()));
    soct::logging::fesvr::debug << "Opening file " << name.data() << " with flags " << host_flags << " and mode "
        << host_mode << "\n";
    const int64_t fd = sysret_errno(open(name.data(), host_flags, host_mode));
    if (fd < 0)
        return fd;
    return m_fds.alloc(fd);
#endif
}

uint64_t syscall_device_t::sys_close(const uint64_t fd, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t) {
    soct::logging::fesvr::debug << "Closing fd " << fd << "\n";
    if (const auto [fd_, type] = m_fds.lookup(fd); type != fds_t::any_t) { // No need to close stdin, stdout, stderr
        close(fd_);
        m_fds.dealloc(fd);
    }
    return 0;
}

uint64_t syscall_device_t::sys_lseek(const uint64_t fd, const uint64_t ptr, const uint64_t whence, uint64_t, uint64_t, uint64_t, uint64_t) {
    soct::logging::fesvr::debug << "Seeking fd " << fd << " to " << ptr << " with whence " << whence << "\n";
    return sysret_errno(lseek(m_fds.lookup(fd).first, ptr, whence));
}

uint64_t syscall_device_t::sys_exit(const uint64_t code, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t) {
    soct::logging::fesvr::info << "Elf is exiting with code " << code << "\n";
    m_htif->set_exitcode(static_cast<int32_t>(code));
    return 0;
}

uint64_t syscall_device_t::sys_openat(const uint64_t dirfd, const uint64_t pname, const uint64_t flags, const uint64_t mode, uint64_t,
                                   uint64_t, uint64_t) {
#ifdef _WIN32
    throw std::runtime_error("openat not implemented on Windows");
#else
    std::vector<char> name(MAX_PATH_SIZE);
    m_cmemif->read(pname, MAX_PATH_SIZE, reinterpret_cast<unsigned char*>(name.data()));
    soct::logging::fesvr::debug << "Opening file " << name.data() << " with flags " << flags << " and mode " << mode
    << " in directory " << dirfd << "\n";
    const sreg_t fd = sysret_errno(openat(m_fds.lookup(dirfd).first, name.data(), flags, mode));
    if (fd < 0)
        return fd;
    return m_fds.alloc(fd);
#endif
}

uint64_t syscall_device_t::sys_pathconf(const uint64_t path, const uint64_t param, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t) {
    soct::logging::fesvr::debug << "Getting pathconf for " << path << " with param " << param << "\n";
    return sysret_errno(pathconf(reinterpret_cast<char*>(path), param));
}


uint64_t syscall_device_t::sys_getmainvars(const uint64_t pbuf, const uint64_t limit, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t) {
    soct::logging::fesvr::debug << "Getting main vars" << "\n";
    const auto& args = m_htif->target_args();
    using chunk_t = uint64_t; // TODO Why 64 bit?
    std::vector<chunk_t> words(args.size() + 3);
    words[0] = args.size();
    words[args.size() + 1] = 0; // argv[argc] = NULL
    words[args.size() + 2] = 0; // envp[0] = NULL

    size_t sz = words.size() * sizeof(chunk_t);
    for (size_t i = 0; i < args.size(); i++) {
        words[i + 1] = pbuf + sz;
        sz += args[i].length() + 1;
    }

    std::vector<char> bytes(sz);
    std::memcpy(bytes.data(), words.data(), sizeof(chunk_t) * words.size());
    for (size_t i = 0; i < args.size(); i++)
        std::strcpy(&bytes[words[i + 1] - pbuf], args[i].c_str());

    if (bytes.size() > limit)
        return -ENOMEM;

    m_cmemif->write(pbuf, bytes.size(), reinterpret_cast<const uint8_t*>(bytes.data()));
    return 0;
}

uint64_t syscall_device_t::syscall_any(const uint32_t sysno, const uint64_t a0, const uint64_t a1, const uint64_t a2, const uint64_t a3,
                                    const uint64_t a4, const uint64_t a5,
                                    const uint64_t a6) {
    soct::logging::fesvr::debug << "Unknown syscall " << sysno << " with args " << a0 << " " << a1 << " " << a2 << " "
        << a3 << " " << a4 << " " << a5 << " " << a6 << "\n";
    return syscall(sysno, a0, a1, a2, a3, a4, a5, a6);
}
