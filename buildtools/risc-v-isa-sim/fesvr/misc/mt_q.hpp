#pragma once

#include <mutex>
#include <condition_variable>
#include <utility>


template <typename T>
class mt_queue_t {
public:
    void push(const T value) {
        wait_for_clear();
        std::lock_guard lock(m_mtx);
        m_val = std::move(value);
        m_cv.notify_one();
    }

    void wait_for_value() {
        std::unique_lock lock(m_mtx);
        m_cv.wait(lock, [this] { return m_val.has_value(); });
        m_cv.notify_one();
    }

    void wait_for_clear() {
        std::unique_lock lock(m_mtx);
        m_cv.wait(lock, [this] { return !m_val.has_value(); });
        m_cv.notify_one();
    }

    T pop() {
        std::lock_guard lock(m_mtx);
        if (!m_val.has_value()) {
            throw std::runtime_error("Queue is empty");
        }
        T value = m_val.value();
        m_val.reset();
        return value;
    }

    T front() {
        std::lock_guard lock(m_mtx);
        if (!m_val.has_value()) {
            throw std::runtime_error("Queue is empty");
        }
        return m_val.value();
    }

    void clear () {
        std::lock_guard lock(m_mtx);
        m_val.reset();
    }

    [[nodiscard]] bool has_value() {
        std::lock_guard lock(m_mtx);
        return m_val.has_value();
    }
private:
    std::optional<T> m_val = std::nullopt;
    std::mutex m_mtx{};
    std::condition_variable m_cv{};
};