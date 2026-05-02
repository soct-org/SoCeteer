#include "argparse.hpp"
#include <iomanip>

namespace soct {

// =============================================================================
// ALL KNOWN ARGUMENTS — add new ones here.
// =============================================================================
static void register_args(arg_parser_t& p) {
    // --- General ---
    p.define_flag  ("help",          "Print this help message and exit");
    p.define_flag  ("h",             "Print this help message and exit");

    // --- Logging ---
    p.define_flag  ("all2console",   "Dump all simulator output to the console");
    p.define_option("log-file",      "Write log to file at <path>. Logging is disabled if omitted");
    p.define_option("log-level",     "Log level: trace|debug|info|warn|err|any", "info");

    // --- Simulation ---
    p.define_option("reset-cycles",  "Number of reset cycles before releasing reset", "100");
    p.define_option("chunk-size",    "DTM memory chunk size in bytes for ELF loading; increase for large ELFs", "4096");
#ifdef VL_TRACE
    p.define_option("vcd-file",      "VCD waveform output file", "dump.vcd");
#endif

    // --- Target ELF / HTIF ---
    p.define_multi ("tgt",           "Argument forwarded to the target ELF, repeatable");
}

// =============================================================================
// arg_parser_t implementation
// =============================================================================

arg_parser_t::arg_parser_t() {
    register_args(*this);
}

void arg_parser_t::define_flag(std::string_view name, std::string_view description) {
    m_defs.push_back({std::string(name), std::string(description), std::nullopt, arg_kind_t::flag});
}

void arg_parser_t::define_option(std::string_view name, std::string_view description,
                                  std::optional<std::string> default_value) {
    m_defs.push_back({std::string(name), std::string(description), std::move(default_value), arg_kind_t::option});
}

void arg_parser_t::define_multi(std::string_view name, std::string_view description) {
    m_defs.push_back({std::string(name), std::string(description), std::nullopt, arg_kind_t::multi});
}

void arg_parser_t::parse(int argc, char* argv[]) {
    if (argc > 0) {
        m_program_name = argv[0];
    }

    for (int i = 1; i < argc; ++i) {
        std::string_view arg = argv[i];

        if (arg.starts_with("--") && arg.size() > 2) {
            const auto eq = arg.find('=');
            if (eq != std::string_view::npos) {
                auto key = std::string(arg.substr(2, eq - 2));
                auto val = std::string(arg.substr(eq + 1));
                if (const auto* def = find_def(key)) {
                    if (def->kind == arg_kind_t::multi) {
                        m_multi[key].push_back(std::move(val));
                    } else {
                        m_options[key] = std::move(val);
                    }
                } else {
                    std::cerr << "[WARN] Unknown argument: --" << key << "\n";
                }
            } else {
                auto key = std::string(arg.substr(2));
                if (const auto* def = find_def(key); def && def->kind == arg_kind_t::flag) {
                    m_flags_set.push_back(key);
                } else {
                    std::cerr << "[WARN] Unknown flag: --" << key << "\n";
                }
            }
        } else if (arg.starts_with("-") && arg.size() > 1) {
            auto key = std::string(arg.substr(1));
            if (const auto* def = find_def(key); def && def->kind == arg_kind_t::flag) {
                m_flags_set.push_back(key);
            } else {
                std::cerr << "[WARN] Unknown flag: -" << key << "\n";
            }
        } else {
            m_positionals.emplace_back(arg);
        }
    }

    if (has_flag("help") || has_flag("h")) {
        print_help();
        std::exit(0);
    }
}

bool arg_parser_t::has_flag(std::string_view name) const {
    return std::ranges::find(m_flags_set, name) != m_flags_set.end();
}

std::optional<std::string> arg_parser_t::get_value(std::string_view name) const {
    if (const auto it = m_options.find(name); it != m_options.end()) {
        return it->second;
    }
    // fall back to registered default
    if (const auto* def = find_def(name); def && def->default_value) {
        return def->default_value;
    }
    return std::nullopt;
}

std::vector<std::string> arg_parser_t::get_values(std::string_view name) const {
    if (const auto it = m_multi.find(name); it != m_multi.end()) {
        return it->second;
    }
    return {};
}

const std::vector<std::string>& arg_parser_t::positionals() const {
    return m_positionals;
}

const std::string& arg_parser_t::program_name() const {
    return m_program_name;
}

void arg_parser_t::print_help() const {
    std::cout << "Usage: " << m_program_name << " [options] <elf-file>\n\n";
    std::cout << "Options:\n";
    for (const auto& def : m_defs) {
        std::string lhs;
        switch (def.kind) {
        case arg_kind_t::flag:
            lhs = "  --" + def.name;
            break;
        case arg_kind_t::option:
            lhs = "  --" + def.name + "=<value>";
            break;
        case arg_kind_t::multi:
            lhs = "  --" + def.name + "=<value> [repeatable]";
            break;
        }
        // align description at column 42
        const int pad = std::max(0, 42 - static_cast<int>(lhs.size()));
        std::cout << lhs << std::string(pad, ' ') << def.description;
        if (def.default_value) {
            std::cout << " [default: " << *def.default_value << "]";
        }
        std::cout << "\n";
    }
}

const arg_def_t* arg_parser_t::find_def(std::string_view name) const {
    for (const auto& def : m_defs) {
        if (def.name == name) return &def;
    }
    return nullptr;
}

namespace globals {
    arg_parser_t args;
}

} // namespace soct
