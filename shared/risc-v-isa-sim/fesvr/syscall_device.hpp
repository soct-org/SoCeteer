// See LICENSE for license details.
#pragma once

#include <vector>
#include <memory>
#include <functional>
#include "device.hpp"
#include "types.hpp"
#include "memif.hpp"

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

    /// Maximum number of syscalls
    constexpr static size_t MAX_SYSCALLS = 2048;

    /// Syscall table
    using flex_syscall_func_t = std::function<uint64_t(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t)>;
    std::vector<flex_syscall_func_t> m_table{MAX_SYSCALLS};

    template <typename F>
    void register_syscall(size_t index, F&& func) {
        m_table[index] = [f = std::forward<F>(func)](uint64_t a0, uint64_t a1, uint64_t a2, uint64_t a3, uint64_t a4, uint64_t a5, uint64_t a6) -> uint64_t {
            if constexpr (std::is_invocable_v<F>) return f();
            else if constexpr (std::is_invocable_v<F, uint64_t>) return f(a0);
            else if constexpr (std::is_invocable_v<F, uint64_t, uint64_t>) return f(a0, a1);
            else if constexpr (std::is_invocable_v<F, uint64_t, uint64_t, uint64_t>) return f(a0, a1, a2);
            else if constexpr (std::is_invocable_v<F, uint64_t, uint64_t, uint64_t, uint64_t>) return f(a0, a1, a2, a3);
            else if constexpr (std::is_invocable_v<F, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t>) return f(a0, a1, a2, a3, a4);
            else if constexpr (std::is_invocable_v<F, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t>) return f(a0, a1, a2, a3, a4, a5);
            else return f(a0, a1, a2, a3, a4, a5, a6);
        };
    }
};
