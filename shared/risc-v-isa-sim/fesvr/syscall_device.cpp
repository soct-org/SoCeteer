#include <cerrno>
#include <stdexcept>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>

#ifdef _WIN32
#include <io.h>
#define read _read
#define write _write
#define close _close
#define lseek _lseek
#define fstat _fstat64
#define stat __stat64
#endif

#include "syscall_device.hpp"
#include "soct/syscalls.h"
#include "soct/soct_ff.h"
#include "htif.hpp"

namespace {
    soct_stat to_soct_stat(const struct stat &st) {
        soct_stat s{};
        s.st_dev = st.st_dev;
        s.st_ino = st.st_ino;
        s.st_mode = st.st_mode;
        s.st_nlink = st.st_nlink;
        s.st_uid = st.st_uid;
        s.st_gid = st.st_gid;
        s.st_rdev = st.st_rdev;
        s.st_size = st.st_size;
#if defined(__APPLE__) || defined(__MACH__)
        s.st_blksize = st.st_blksize;
        s.st_blocks = st.st_blocks;
        s.st_atime_sec = st.st_atimespec.tv_sec;
        s.st_mtime_sec = st.st_mtimespec.tv_sec;
        s.st_ctime_sec = st.st_ctimespec.tv_sec;
#elif defined(_WIN32) || defined(__MINGW32__)
        s.st_blksize = 512;
        s.st_blocks = (s.st_size + s.st_blksize - 1) / s.st_blksize;
        s.st_atime_sec = st.st_atime;
        s.st_mtime_sec = st.st_mtime;
        s.st_ctime_sec = st.st_ctime;
#else
        s.st_blksize = st.st_blksize;
        s.st_blocks = st.st_blocks;
        s.st_atime_sec = st.st_atim.tv_sec;
        s.st_mtime_sec = st.st_mtim.tv_sec;
        s.st_ctime_sec = st.st_ctim.tv_sec;
#endif
        return s;
    }

    int translate_open_flags(const sc_arg_t guest_flags) {
        int host_flags = 0;

        switch (guest_flags & SOCT_O_ACCMODE) {
            case SOCT_O_WRONLY:
                host_flags |= O_WRONLY;
                break;
            case SOCT_O_RDWR:
                host_flags |= O_RDWR;
                break;
            case SOCT_O_RDONLY:
            default:
                host_flags |= O_RDONLY;
                break;
        }

        if (guest_flags & SOCT_O_APPEND)
#ifdef O_APPEND
            host_flags |= O_APPEND;
#endif
        if (guest_flags & SOCT_O_CREAT)
#ifdef O_CREAT
            host_flags |= O_CREAT;
#endif
        if (guest_flags & SOCT_O_TRUNC)
#ifdef O_TRUNC
            host_flags |= O_TRUNC;
#endif
        if (guest_flags & SOCT_O_EXCL)
#ifdef O_EXCL
            host_flags |= O_EXCL;
#endif
        if (guest_flags & SOCT_O_SYNC)
#ifdef O_SYNC
            host_flags |= O_SYNC;
#endif
        if (guest_flags & SOCT_O_NONBLOCK)
#ifdef O_NONBLOCK
            host_flags |= O_NONBLOCK;
#endif
        if (guest_flags & SOCT_O_NOFOLLOW)
#ifdef O_NOFOLLOW
            host_flags |= O_NOFOLLOW;
#endif
        if (guest_flags & SOCT_O_CLOEXEC)
#ifdef O_CLOEXEC
            host_flags |= O_CLOEXEC;
#endif
        if (guest_flags & SOCT_O_DIRECTORY)
#ifdef O_DIRECTORY
            host_flags |= O_DIRECTORY;
#endif
        if (guest_flags & SOCT_O_NOCTTY)
#ifdef O_NOCTTY
            host_flags |= O_NOCTTY;
#endif

        return host_flags;
    }

    void make_guest_stdio_stat(soct_stat &s) {
        s = {};
        s.st_mode = 0020000 | 0666; // S_IFCHR | rw-rw-rw-
        s.st_nlink = 1;
        s.st_blksize = 64;
    }

    int64_t sysret_errno(int64_t ret) {
        return ret == -1 ? -errno : ret;
    }

    sc_resp_t syscall_any(const sc_type_t sysno, const sc_arg_t a0, const sc_arg_t a1, const sc_arg_t a2,
                          const sc_arg_t a3, const sc_arg_t a4, const sc_arg_t a5, const sc_arg_t a6) {
        soct::logging::fesvr::debug << "Unknown syscall " << sysno << " with args " << a0 << " " << a1 << " " << a2 <<
                " "
                << a3 << " " << a4 << " " << a5 << " " << a6 << "\n";
#ifdef _WIN32
        return -ENOSYS;
#else
        return sysret_errno(syscall(sysno, a0, a1, a2, a3, a4, a5, a6));
#endif
    }
} // namespace

syscall_device_t::~syscall_device_t() = default;

syscall_device_t::syscall_device_t(const std::shared_ptr<htif_t> &htif, const std::shared_ptr<chunked_memif_t> &cmemif)
    : device_t(htif, cmemif) {
    using namespace std::literals;

    register_syscall(SOCT_READ, [this](sc_arg_t fd, sc_arg_t pbuf, sc_arg_t len) -> sc_resp_t {
        soct::logging::fesvr::debug << "Reading " << len << " bytes from fd " << fd << " to " << pbuf << "\n";
        std::vector<char> buf(len);
        const ssize_t ret = sysret_errno(read(fd, buf.data(), len));
        if (ret >= 0)
            m_cmemif->write(pbuf, ret, reinterpret_cast<uint8_t *>(buf.data()));
        return ret;
    });

    register_syscall(SOCT_WRITE, [this](sc_arg_t fd, sc_arg_t pbuf, sc_arg_t len) -> sc_resp_t {
        soct::logging::fesvr::debug << "Writing " << len << " bytes to fd " << fd << " from " << pbuf << "\n";
        std::vector<char> buf(len);
        m_cmemif->read(pbuf, len, reinterpret_cast<uint8_t *>(buf.data()));

        if (fd == 1) {
            soct::logging::elf::to_stdout << std::string(buf.data(), len);
            return len;
        }
        if (fd == 2) {
            soct::logging::elf::to_stderr << std::string(buf.data(), len);
            return len;
        }
        return sysret_errno(write(fd, buf.data(), len));
    });

    register_syscall(SOCT_FSTAT, [this](sc_arg_t fd, sc_arg_t pbuf) -> sc_resp_t {
        soct::logging::fesvr::debug << "fstat on fd " << fd << " to " << pbuf << "\n";

        soct_stat s{};

        int ret;
        if (fd == 0 || fd == 1 || fd == 2) {
            make_guest_stdio_stat(s);
            ret = 0;
        } else {
            struct stat st{};
            ret = sysret_errno(fstat(fd, &st));
            if (ret == 0)
                s = to_soct_stat(st);
        }

        if (ret == 0) {
            soct::logging::fesvr::debug << "fstat result: st_size=" << s.st_size
                    << " st_mode=0" << std::oct << s.st_mode << std::dec
                    << " st_blocks=" << s.st_blocks
                    << " st_atime=" << s.st_atime_sec << "\n";
            m_cmemif->write(pbuf, sizeof(s), reinterpret_cast<const uint8_t *>(&s));
        }
        return ret;
    });

    register_syscall(SOCT_OPEN, [this](sc_arg_t pname, sc_arg_t flags, sc_arg_t mode) -> sc_resp_t {
        auto host_mode = mode;
        auto host_flags = static_cast<int>(translate_open_flags(flags));

        const auto name = m_cmemif->read_string(pname, MAX_PATH_SIZE);
        soct::logging::fesvr::debug << "Opening file " << name << " with flags " << host_flags << " and mode "
                << host_mode << "\n";
#ifdef _WIN32
        return sysret_errno(_open(name.c_str(), host_flags, host_mode));
#else
        return sysret_errno(open(name.c_str(), host_flags, host_mode));
#endif
    });

    register_syscall(SOCT_CLOSE, [](sc_arg_t fd) -> sc_resp_t {
        soct::logging::fesvr::debug << "Closing fd " << fd << "\n";
        if (fd == 0 || fd == 1 || fd == 2)
            return 0;
        return sysret_errno(close(fd));
    });

    register_syscall(SOCT_LSEEK, [](sc_arg_t fd, sc_arg_t ptr, sc_arg_t whence) -> sc_resp_t {
        soct::logging::fesvr::debug << "Seeking fd " << fd << " to " << ptr << " with whence " << whence << "\n";
        return sysret_errno(lseek(fd, ptr, whence));
    });

    register_syscall(SOCT_EXIT, [this](sc_arg_t code) -> sc_resp_t {
        soct::logging::fesvr::info << "Elf is exiting with code " << code << "\n";
        m_htif->set_exitcode(static_cast<int32_t>(code));
        return 0;
    });

    register_syscall(SOCT_OPENAT, [this](sc_arg_t dirfd, sc_arg_t pname, sc_arg_t flags, sc_arg_t mode) -> sc_resp_t {
        int host_dirfd = (int) dirfd;
        const auto name = m_cmemif->read_string(pname, MAX_PATH_SIZE);
        const auto host_flags = translate_open_flags(flags);
        soct::logging::fesvr::debug << "Opening file " << name << " with flags " << flags << " and mode " << mode
                << " in directory " << dirfd << "\n";

#ifdef _WIN32
        if (host_dirfd == RV_AT_FDCWD || host_dirfd == -100) {
            return sysret_errno(_open(name.c_str(), host_flags, mode));
        }
        return -ENOSYS;
#else
        if (host_dirfd == RV_AT_FDCWD)
            host_dirfd = AT_FDCWD;

        return sysret_errno(openat(host_dirfd, name.c_str(), host_flags, mode));
#endif
    });

    register_syscall(SOCT_PATHCONF, [this](sc_arg_t path, sc_arg_t param) -> sc_resp_t {
        soct::logging::fesvr::debug << "Getting pathconf for " << path << " with param " << param << "\n";
#ifdef _WIN32
        // Windows does not implement pathconf natively.
        // Param 3 = _PC_NAME_MAX, 4 = _PC_PATH_MAX according to standard POSIX layout.
        switch (param) {
            case 3: return 255;
            case 4: return 260; // MAX_PATH
            default: return -ENOSYS;
        }
#else
        const auto host_path = m_cmemif->read_string(path, MAX_PATH_SIZE);
        return sysret_errno(pathconf(host_path.c_str(), param));
#endif
    });

    // TODO implement SOCT_FOPEN
    register_syscall(SOCT_GET_MAINVARS, [this](sc_arg_t pbuf, sc_arg_t limit) -> sc_resp_t {
        soct::logging::fesvr::debug << "Getting main vars" << "\n";
        const auto &args = m_htif->target_args();

        std::vector<guest_reg_t> words(args.size() + 3);
        words[0] = args.size();
        words[args.size() + 1] = 0; // argv[argc] = NULL
        words[args.size() + 2] = 0; // envp[0] = NULL

        size_t sz = words.size() * sizeof(guest_reg_t);
        for (size_t i = 0; i < args.size(); i++) {
            words[i + 1] = pbuf + sz;
            sz += args[i].length() + 1;
        }

        std::vector<char> bytes(sz);
        std::memcpy(bytes.data(), words.data(), sizeof(guest_reg_t) * words.size());
        for (size_t i = 0; i < args.size(); i++)
            std::strcpy(&bytes[words[i + 1] - pbuf], args[i].c_str());

        if (bytes.size() > limit)
            return -ENOMEM;

        m_cmemif->write(pbuf, bytes.size(), reinterpret_cast<const uint8_t *>(bytes.data()));
        return 0;
    });

    register_syscall(SOCT_HTIF_DEV_TEST, []() -> sc_resp_t { return 0; });

    register_command(0, [this](const cmd_t &command) { handle_syscall(command); }, "syscall"sv);
}

void syscall_device_t::handle_syscall(const cmd_t &cmd) {
    std::array<sc_arg_t, 8> htif_mem{};
    const auto mm = cmd.payload();
    m_cmemif->read(mm, sizeof(htif_mem), reinterpret_cast<uint8_t *>(htif_mem.data()));

    const uint32_t syscall = htif_mem[0];
    const sc_arg_t a0 = htif_mem[1];
    const sc_arg_t a1 = htif_mem[2];
    const sc_arg_t a2 = htif_mem[3];
    const sc_arg_t a3 = htif_mem[4];
    const sc_arg_t a4 = htif_mem[5];
    const sc_arg_t a5 = htif_mem[6];
    const sc_arg_t a6 = htif_mem[7];

    if (syscall >= m_table.size() || !m_table[syscall]) {
#ifdef PASS_UNKNOWN_SYSCALLS
        htif_mem[0] = syscall_any(syscall, a0, a1, a2, a3, a4, a5, a6);
#else
        throw std::runtime_error("Unimplemented syscall: " + std::to_string(syscall));
#endif
    } else {
        htif_mem[0] = m_table[syscall](a0, a1, a2, a3, a4, a5, a6);
    }
    m_cmemif->write(mm, sizeof(htif_mem), reinterpret_cast<uint8_t *>(htif_mem.data()));
    soct::logging::fesvr::debug << "Syscall " << syscall << " handled\n";
    cmd.respond(1);
}
