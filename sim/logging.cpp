#include "logging.hpp"

#include <disasm.h>
#include <iostream>

namespace soct::logging {

    namespace globals {
        log_level_t log_level = INFO;
        bool all2console = false;
        std::optional<std::ofstream> log_stream = std::nullopt;
    }

    bool is_log_file_enabled() {
        return globals::log_stream.has_value();
    }

    void set_log_level(const log_level_t level) {
        globals::log_level = level;
    }

    void init_logging(const std::string& file) {
        globals::log_stream = std::ofstream(file);
    }

    void close_logging() {
        if (globals::log_stream.has_value()) {
            globals::log_stream->close();
        }
    }

    bool is_log_file_open() {
        return globals::log_stream.has_value() && globals::log_stream->is_open();
    }

    bool is_rocketchip_log(const std::string_view& s) {
        return !s.empty() && s.front() == 'C' && s.size() == globals::rocket_chip_message_len;
    }

    std::string_view get_rocket_chip_dasm(const std::string_view& s) {
        if (s.size() < globals::rocket_chip_dasm_start + globals::rocket_chip_dasm_len) {
            return {};
        }
        return s.substr(globals::rocket_chip_dasm_start, globals::rocket_chip_dasm_len);
    }

    bool is_debug_msg(const std::string_view& s) {
        return s.starts_with(globals::debug_prefix);
    }

    bool is_info_msg(const std::string_view& s) {
        return s.starts_with(globals::info_prefix);
    }

    bool is_warning_msg(const std::string_view& s) {
        return s.starts_with(globals::warning_prefix);
    }

    bool is_error_msg(const std::string_view& s) {
        return s.starts_with(globals::error_prefix);
    }

    bool prefix_within_log_level(const std::string_view& s, const log_level_t level) {
        switch (level) {
        case DEBUG:
            return is_error_msg(s) || is_warning_msg(s) || is_info_msg(s) || is_debug_msg(s);
        case INFO:
            return is_error_msg(s) || is_warning_msg(s) || is_info_msg(s);
        case WARN:
            return is_error_msg(s) || is_warning_msg(s);
        case ERR:
            return is_error_msg(s);
        default:
            return false;
        }
    }

    log_level_t parse_log_level(const std::string_view s) {
        if (s == "debug") return DEBUG;
        if (s == "info")  return INFO;
        if (s == "warn")  return WARN;
        if (s == "err" || s == "error") return ERR;
        if (s == "any")   return ANY;
#ifdef ENABLE_TRACE
        if (s == "trace") return TRACE1;
#endif
        return INFO; // default
    }

    logger_t::logger_t(const log_level_t& level, const std::string_view& prefix, const bool log_to_file, const bool log_to_console)
        : m_level(level),
          m_prefix(prefix),
          m_log_to_file(log_to_file),
          m_log_to_console(log_to_console),
          m_console_out(level == ERR || level == WARN ? std::cerr : std::cout) {
    }

    void logger_t::flush() const {
        if (m_log_to_file && is_log_file_enabled()) {
            globals::log_stream.value().flush();
        }
        if (m_log_to_console) {
            m_console_out.flush();
        }
    }

    void logger_t::init_progress_bar(const std::string& prefix, const std::string& suffix) {
        if (m_log_to_console) {
            m_pb_prefix = prefix;
            m_pb_suffix = suffix;
        }
    }

    void logger_t::close_progress_bar() {
        m_pb_prefix = std::nullopt;
        m_pb_suffix = std::nullopt;
        if (m_log_to_console) {
            m_console_out << "\n";
        }
    }

    bool logger_t::within_log_level() const {
        return m_level >= globals::log_level;
    }

    namespace elf {
        logger_t to_stderr(ANY, "\033[32m[ELF]\033[0m ");
        logger_t to_stdout(ANY, "\033[32m[ELF]\033[0m ");
    }

    namespace rocketchip {
        logger_t console(INFO, "[ROC] ");
        logger_t file(ANY, "", true, false);
    }

    namespace circuit {
        logger_t console(ANY, "\033[33m[CIR]\033[0m ");
        logger_t file(ANY, "", true, false);
    }

    namespace fesvr {
#ifdef ENABLE_TRACE
        logger_t trace(TRACE1, "\033[34m[FES]\033[0m ");
#endif
        logger_t debug(DEBUG, "\033[36m[FES]\033[0m ");
        logger_t info(INFO, "\033[36m[FES]\033[0m ");
        logger_t warn(WARN, "\033[36m[FES]\033[0m ");
        logger_t error(ERR, "\033[36m[FES]\033[0m ");
    }

}

