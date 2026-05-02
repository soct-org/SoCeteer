#pragma once

#include <string>
#include <string_view>
#include <vector>
#include <map>
#include <optional>
#include <algorithm>
#include <iostream>

namespace soct {

    enum class arg_kind_t { flag, option, multi };

    struct arg_def_t {
        std::string         name;
        std::string         description;
        std::optional<std::string> default_value; ///< nullopt = disabled/absent by default
        arg_kind_t          kind;
    };

    class arg_parser_t {
    public:
        /// Constructs the parser and registers all known arguments.
        arg_parser_t();
        ~arg_parser_t() = default;

        // ---------------------------------------------------------------
        // Registration — call these before parse(), typically in the ctor.
        // ---------------------------------------------------------------
        void define_flag  (std::string_view name, std::string_view description);
        void define_option(std::string_view name, std::string_view description,
                           std::optional<std::string> default_value = std::nullopt);
        void define_multi (std::string_view name, std::string_view description);

        // ---------------------------------------------------------------
        // Parsing
        // ---------------------------------------------------------------
        void parse(int argc, char* argv[]);

        // ---------------------------------------------------------------
        // Querying
        // ---------------------------------------------------------------
        /// True if a flag was present on the command line.
        [[nodiscard]] bool has_flag(std::string_view name) const;

        /// Returns the value of a single-value option, or its registered default.
        [[nodiscard]] std::optional<std::string> get_value(std::string_view name) const;

        /// Returns all values for a multi-value option.
        [[nodiscard]] std::vector<std::string> get_values(std::string_view name) const;

        /// Returns positional arguments (not starting with - or --).
        [[nodiscard]] const std::vector<std::string>& positionals() const;

        /// Returns the program name (argv[0]).
        [[nodiscard]] const std::string& program_name() const;

        // ---------------------------------------------------------------
        // Help
        // ---------------------------------------------------------------
        void print_help() const;

    private:
        std::string                    m_program_name;
        std::vector<arg_def_t>         m_defs;
        std::vector<std::string>       m_flags_set;
        std::map<std::string, std::string,              std::less<>> m_options;
        std::map<std::string, std::vector<std::string>, std::less<>> m_multi;
        std::vector<std::string>       m_positionals;

        [[nodiscard]] const arg_def_t* find_def(std::string_view name) const;
    };

    namespace globals {
        extern arg_parser_t args;
    }

} // namespace soct
