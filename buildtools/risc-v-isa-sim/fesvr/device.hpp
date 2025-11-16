#pragma once
#include "types.hpp"
#include "memif.hpp"
#include "asserts.hpp"
#include "logging.hpp"

/// A command object.
class cmd_t {
public:
    cmd_t(const uint64_t tohost, callback_t cb) : m_tohost(tohost), m_cb(std::move(cb)) {
    }

    [[nodiscard]] uint8_t device() const { return m_tohost >> 56; }

    [[nodiscard]] uint8_t cmd() const { return m_tohost >> 48; }

    [[nodiscard]] uint64_t payload() const { return m_tohost << 16 >> 16; }

    void respond(const uint64_t resp) const { m_cb((m_tohost >> 48 << 48) | (resp << 16 >> 16)); }

private:
    /// The tohost value
    uint64_t m_tohost;
    /// The callback function, which is called when the command is responded to.
    callback_t m_cb;
};

/// Command function type
using command_func_t = std::function<void(cmd_t)>;

class htif_t;

/// A device.
class device_t {
public:
    virtual ~device_t() = default;

    device_t(std::shared_ptr<htif_t> htif, std::shared_ptr<chunked_memif_t> cmemif) :
        m_htif(std::move(htif)),
        m_cmemif(std::move(cmemif)) {
    }

    void handle_command(const cmd_t& cmd) const {
        m_command_handlers[cmd.cmd()](cmd);
    }

    /// Get the name of the device
    virtual std::string_view name() = 0;

    /// Tick the device. Empty by default.
    virtual void tick() {
    }

    static constexpr size_t MAX_COMMANDS = 256;
    static constexpr size_t MAX_DEVICE_LENGTH = 256;

protected:
    /// HTIF instance
    std::shared_ptr<htif_t> m_htif{};

    /// Memory interface
    std::shared_ptr<chunked_memif_t> m_cmemif{};

    /// Register a command
    void register_command(const size_t cmd, command_func_t handler, const std::string_view device_name) {
        massert(cmd < MAX_COMMANDS, "Command number out of range");
        massert(device_name.size() < MAX_DEVICE_LENGTH, "Device name too long");
        m_command_handlers[cmd] = std::move(handler);
        m_command_names[cmd] = device_name;
    }

private:
    void handle_identify(cmd_t cmd) {
    }

    // Command handlers (static)
    std::array<command_func_t, MAX_COMMANDS> m_command_handlers;
    std::array<std::string, MAX_DEVICE_LENGTH> m_command_names;
};

/// A list of devices
class device_list_t {
public:
    static constexpr size_t MAX_DEVICES = 256;

    device_list_t() = default;

    void register_device(device_t* dev) {
        m_devices.push_back(dev);
    }

    void handle_command(const cmd_t& cmd) const {
        const auto dev = cmd.device();
        if (cmd.device() >= m_devices.size()) {
            soct::logging::fesvr::warn << "Device number out of range: " << dev << std::endl;
            return;
        }
        m_devices[dev]->handle_command(cmd);
    }

    void tick() {
        for (auto& dev : m_devices) {
            dev->tick();
        }
    }

private:
    /// The list of devices as a linked list. The index is the device number, i.e. cmd.device().
    std::vector<device_t*> m_devices;
};
