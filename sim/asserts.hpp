#pragma once

#include <cassert>
#include <iostream>
#include <stdexcept>

// Asserts that a condition is true, otherwise prints a message to stderr and aborts.
#ifdef NDEBUG
#ifdef FORCE_ASSERTS
#define massert(condition, message) assert_(condition, message, __FUNCTION__, __FILE__, __LINE__)
#else
#define massert(condition, message) ((void)0)
#endif
#else
#define massert(condition, message) assert_(condition, message, __FUNCTION__, __FILE__, __LINE__)
#endif

inline void assert_(const bool condition, const std::string& message, const char* function, const char* file, int line) {
    if (!condition) {
        std::cerr << "Assertion failed: (" << message << ") at " << file << ":" << line << "\n";
        std::abort();
    }
}

