#pragma once
#include <algorithm>
#include <cassert>
#include <iostream>
#include <chrono>
#include <map>
#include <memory>
#include <vector>
#include <numeric>

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

        static std::size_t hash_str(const std::string& s) {
            static std::hash<std::string> h;
            return h(s);
        }

        template <typename T>
        static std::size_t hash_int(auto s) {
            static std::hash<T> h;
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

        for (const auto& [identifier, measures] : m_measures) {
            assert(measures->size() % 2 == 0 && "The number of measures should be even (start and end)");
            std::vector<uint64_t> times;
            times.reserve(measures->size() / 2);
            const auto [file, line, func_name, _] = identifier;
            for (size_t i = 0; i < measures->size(); i += 2) {
                const auto start = measures->at(i);
                const auto end = measures->at(i + 1);
                assert(start < end && "The start time should be less than the end time");
                const auto duration = end - start;
                times.push_back(duration);
            }

            auto to_ms = [this]<typename T>(T time) {
                return static_cast<double>(time) / 1'000'000.0;
            };

            const uint64_t sum = std::accumulate(times.begin(), times.end(), 0llu);
            const double mean = to_ms(static_cast<double>(sum) / static_cast<double>(times.size()));
            // sort the vector to calculate the median
            std::ranges::sort(times);
            const size_t center = times.size() / 2;
            const auto median = to_ms(times.size() % 2 == 0
                                          ? (times.at(center - 1) + times.at(center)) / 2
                                          : times.at(center));
            const auto min = to_ms(*std::ranges::min_element(times));
            const auto max = to_ms(*std::ranges::max_element(times));

            std::cout << "What: " << func_name << "\n";
            std::cout << "Where: " << file << ":" << line << "\n";
            std::cout << "Mean: " << mean << "ms\n";
            std::cout << "Median: " << median << "ms\n";
            std::cout << "Min: " << min << "ms\n";
            std::cout << "Max: " << max << "ms\n";
            std::cout << "Ticks per sec (mean): " << format_hz(mean > 0 ? 1000 / mean : 0) << "\n";
            std::cout << "Total: " << to_ms(sum) << "ms\n";
            std::cout << "Number of runs: " << times.size() << "\n";
            std::cout << "-----------------------------------\n";
        }
    }

    void add_time(const Identifier& id) {
        const auto time = std::chrono::high_resolution_clock::now().time_since_epoch().count();
        if (!m_measures.contains(id)) {
            m_measures.emplace(id, std::make_unique<std::vector<uint64_t>>());
        }
        m_measures[id]->push_back(time);
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
        m_stack.emplace_back(id, std::chrono::high_resolution_clock::now().time_since_epoch().count());
    }

    void pop() {
        const auto end = std::chrono::high_resolution_clock::now().time_since_epoch().count();
        const auto [id, start] = m_stack.back();
        m_stack.pop_back();
        if (!m_measures.contains(id)) {
            m_measures.emplace(id, std::make_unique<std::vector<uint64_t>>());
        }
        m_measures[id]->push_back(start);
        m_measures[id]->push_back(end);
    }

    static const char* alt_or_default(const std::string_view& alt = "") {
        if (alt.empty()) {
            return "CODE-BLOCK";
        }
        return alt.data();
    }

private:
    std::map<Identifier, std::unique_ptr<std::vector<uint64_t>>> m_measures;
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