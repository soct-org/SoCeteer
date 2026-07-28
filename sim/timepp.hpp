#pragma once
#include <algorithm>
#include <cassert>
#include <chrono>
#include <cstdint>
#include <iostream>
#include <map>
#include <optional>
#include <random>
#include <string_view>
#include <vector>

class Timepp {
public:
    class Identifier {
    public:
        const char* file_name;
        int32_t line;
        const char* func_name;
        // the hash:
        const size_t hash;

        Identifier(const char* file_name, const int32_t line, const char* func_name)
            : file_name(file_name), line(line), func_name(func_name),
              hash(hash_str(file_name) ^ hash_int<int32_t>(line) ^ hash_str(func_name)) {
        }

        bool operator<(const Identifier& other) const {
            return hash < other.hash;
        }

        bool operator==(const Identifier& other) const {
            return hash == other.hash;
        }

        // string_view on purpose: the string overload would build a heap-allocated
        // temporary from the char* on EVERY construction - and identifiers are
        // constructed once per measurement, i.e. once per simulated cycle.
        static std::size_t hash_str(const std::string_view s) {
            static constexpr std::hash<std::string_view> h;
            return h(s);
        }

        template <typename T>
        static std::size_t hash_int(auto s) {
            static constexpr std::hash<T> h;
            return h(s);
        }
    };

    void reset() {
        m_measures.clear();
    }


    ~Timepp() {
        summarize();
        reset();
    }

    static std::string format_hz(const double hz) {
        if (hz < 1e3) {
            return std::to_string(hz) + " Hz\n";
        }
        if (hz < 1e6) {
            return std::to_string(hz / 1e3) + " kHz\n";
        }
        if (hz < 1e9) {
            return std::to_string(hz / 1e6) + " MHz\n";
        }
        return std::to_string(hz / 1e9) + " GHz\n";
    }


    void summarize() {

        std::cout << "-----------------------------------\n";
        std::cout << "Timepp Summary\n";
        std::cout << "-----------------------------------\n";

        for (auto& [identifier, stats] : m_measures) {
            assert(!stats.pending_start && "A measurement is still open (push without pop)");
            if (stats.count == 0) {
                continue;
            }
            const auto [file, line, func_name, _] = identifier;

            auto to_ms = []<typename T>(T time) {
                return static_cast<double>(time) / 1'000'000.0;
            };

            const double mean = to_ms(static_cast<double>(stats.sum) / static_cast<double>(stats.count));

            if (stats.count == 1) {
                std::cout << "What: " << func_name << "\n";
                std::cout << "Where: " << file << ":" << line << "\n";
                std::cout << "Time: " << mean << "ms\n";
                std::cout << "Ticks per sec: " << format_hz(mean > 0 ? 1000 / mean : 0) << "\n";
                std::cout << "-----------------------------------\n";
            } else {
                std::vector<uint64_t> sample = stats.reservoir;
                std::ranges::sort(sample);
                const size_t center = sample.size() / 2;
                const auto median = to_ms(sample.size() % 2 == 0
                                              ? (sample.at(center - 1) + sample.at(center)) / 2
                                              : sample.at(center));

                std::cout << "What: " << func_name << "\n";
                std::cout << "Where: " << file << ":" << line << "\n";
                std::cout << "Mean: " << mean << "ms\n";
                std::cout << "Median" << (stats.count > ReservoirCap ? " (sampled)" : "")
                          << ": " << median << "ms\n";
                std::cout << "Min: " << to_ms(stats.min) << "ms\n";
                std::cout << "Max: " << to_ms(stats.max) << "ms\n";
                std::cout << "Ticks per sec (mean): " << format_hz(mean > 0 ? 1000 / mean : 0) << "\n";
                std::cout << "Total: " << to_ms(stats.sum) << "ms\n";
                std::cout << "Number of runs: " << stats.count << "\n";
                std::cout << "-----------------------------------\n";
            }
        }
    }

    void add_time(const Identifier& id) {
        const auto time = now();
        auto& stats = stats_of(id);
        // Calls come in strict start/end pairs (the timest/timefn macros): the first
        // opens the measurement, the second closes it into one duration sample.
        if (stats.pending_start) {
            assert(time >= *stats.pending_start && "The start time should not be after the end time");
            const auto start = *stats.pending_start;
            stats.pending_start.reset();
            stats.record(time - start);
        } else {
            stats.pending_start = time;
        }
    }

    auto add_func(const Identifier& id, auto func) -> std::enable_if_t<!std::is_void_v<decltype(func())>, decltype(func())> {
        add_time(id);
        auto result = func();
        add_time(id);
        return result;
    }

    auto add_func(const Identifier& id, auto func) -> std::enable_if_t<std::is_void_v<decltype(func())>, void> {
        add_time(id);
        func();
        add_time(id);
    }

    void push(const Identifier& id) {
        m_stack.emplace_back(id, now());
    }

    void pop() {
        const auto end = now();
        const auto [id, start] = m_stack.back();
        m_stack.pop_back();
        assert(end >= start && "The start time should not be after the end time");
        stats_of(id).record(end - start);
    }

    static const char* alt_or_default(const std::string_view& alt = "") {
        if (alt.empty()) {
            return "CODE-BLOCK";
        }
        return alt.data();
    }

private:
    // The cycle loop produces one sample per simulated clock, so storing every
    // sample would grow the heap without bound (16 bytes per cycle - gigabytes
    // over a Linux boot). Statistics stream instead: mean/min/max/total/count are
    // exact in O(1) memory, and the median comes from a fixed-size uniform sample
    // (reservoir sampling, algorithm R) - exact until the reservoir fills, an
    // unbiased estimate afterwards (the summary marks it "(sampled)" then).
    static constexpr size_t ReservoirCap = 4096;

    struct Stats {
        uint64_t count = 0;
        uint64_t sum = 0;
        uint64_t min = UINT64_MAX;
        uint64_t max = 0;
        std::optional<uint64_t> pending_start; // add_time's open measurement
        std::vector<uint64_t> reservoir;
        // Deterministic on purpose: reruns of the same simulation summarize the
        // same way.
        std::minstd_rand rng{0x9E3779B9u};

        void record(const uint64_t duration) {
            sum += duration;
            min = std::min(min, duration);
            max = std::max(max, duration);
            if (reservoir.size() < ReservoirCap) {
                reservoir.push_back(duration);
            } else if (const auto slot = rng() % (count + 1); slot < ReservoirCap) {
                reservoir[slot] = duration;
            }
            count++;
        }
    };

    // steady_clock, not high_resolution_clock: the latter aliases the system clock
    // on some platforms, which NTP may step backwards mid-measurement.
    static uint64_t now() {
        return std::chrono::steady_clock::now().time_since_epoch().count();
    }

    Stats& stats_of(const Identifier& id) {
        return m_measures.try_emplace(id).first->second;
    }

    std::map<Identifier, Stats> m_measures;
    std::vector<std::tuple<Identifier, uint64_t>> m_stack;
};


inline Timepp timepp;

/// Measure the time it takes to run a statement or a block of code.
#define timest(...) timepp.add_time({__FILE__, __LINE__, #__VA_ARGS__}); __VA_ARGS__ ; timepp.add_time({__FILE__, __LINE__, #__VA_ARGS__})

/// Measure the time it takes to run a function.
#define timefn(...) timepp.add_func({__FILE__, __LINE__, #__VA_ARGS__}, [&]() { return (__VA_ARGS__); })

/// Measure the time it takes to run a function in global scope.
#define timegb(...) timepp.add_func({__FILE__, __LINE__, #__VA_ARGS__}, []() { return (__VA_ARGS__); })

/// Start measuring the time it takes to run a block of code.
#define timepush(...) timepp.push({__FILE__, __LINE__, Timepp::alt_or_default(__VA_ARGS__)})

/// Stop measuring the time it takes to run a block of code.
#define timepop(...) timepp.pop()
