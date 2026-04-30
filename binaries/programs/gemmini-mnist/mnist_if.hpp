#pragma once
#include "model.h"
#include "include/gemmini_testutils.h"
#include <algorithm>
constexpr size_t NUM_CLASSES = 10; // Number of classes for MNIST

#ifndef SOCT_CONFIG_MNIST_CACHE_SIZE
constexpr size_t CACHE_SIZE = 1; // Caches images for repeated inference of the same image
#else
constexpr size_t CACHE_SIZE = SOCT_CONFIG_MNIST_CACHE_SIZE;
#endif

// (actual score, faulty score) for each class
using result_t = std::array<std::pair<elem_t, elem_t>, NUM_CLASSES>;

template <size_t width = 28, size_t height = 28, size_t channels = 1, size_t cache_size = CACHE_SIZE>
class mnist_if_t {
public:
    static constexpr size_t image_size() {
        return width * height * channels;
    }

    // Load an image from cache or file (if not cached)
    std::optional<std::array<elem_t, image_size()>>
    get_image(const std::string& path) {
        // Check if image is in cache
        for (size_t i = 0; i < cache_size; ++i) {
            if (cache_keys[i] == path) {
                return cache_images[i];
            }
        }
        // Not in cache, load from file
        std::array<elem_t, image_size()> img{};
        FILE* file = fopen(path.c_str(), "rb+");
        if (!file) {
            printf("Failed to open image file: %s\n", path.c_str());
            return std::nullopt;
        }
        if (fread(img.data(), sizeof(elem_t), image_size(), file) != image_size()) {
            printf("Failed to read image file: %s\n", path.c_str());
            fclose(file);
            return std::nullopt;
        }
        fclose(file);

        // Store in cache (overwrite the oldest entry)
        cache_images[cache_index] = img;
        cache_keys[cache_index] = path;
        cache_index = (cache_index + 1) % cache_size; // Circular buffer
        return img;
    }

    // Run inference on a cached or loaded image
    std::optional<std::array<std::array<elem_t, NUM_CLASSES>, 1>>
    infer2D(const std::string& path) {
        std::array<std::array<elem_t, image_size()>, 1> img{};
        std::array<std::array<elem_t, NUM_CLASSES>, 1> result{};

        auto loaded = get_image(path);
        if (!loaded) {
            return std::nullopt;
        }
        img[0] = *loaded;

        entry(reinterpret_cast<const elem_t(*)[image_size()]>(img.data()),
              reinterpret_cast<elem_t(*)[NUM_CLASSES]>(result.data()));
        return result;
    }

    // Run inference on a cached or loaded image
    std::optional<std::array<elem_t, NUM_CLASSES>>
    infer(const std::string& path) {
        auto res = infer2D(path);
        if (!res) {
            return std::nullopt;
        }
        return res.value()[0];
    }


    void clear() {
        for (size_t i = 0; i < cache_size; ++i) {
            cache_keys[i].clear();
        }
        cache_index = 0;
    }

private:
    std::array<std::array<elem_t, image_size()>, cache_size> cache_images{};
    std::array<std::string, cache_size> cache_keys{};
    size_t cache_index = 0; // Points to the next slot to overwrite
};

/// Convert file content to a vector of strings
inline std::vector<std::string> f2str(FILE* file) {
    std::vector<std::string> lines;
    if (file == nullptr) {
        perror("Failed to open file");
        return lines; // Return empty vector on error
    }
    char buffer[256];
    while (fgets(buffer, sizeof(buffer), file) != nullptr) {
        if (const size_t len = strlen(buffer); len > 0 && buffer[len - 1] == '\n') {
            buffer[len - 1] = '\0'; // Remove newline character
        }
        lines.emplace_back(buffer);
    }
    fclose(file);
    return lines;
}

/// Convert file content to a vector of bytes
template <typename T = uint8_t>
std::vector<T> f2b(FILE* file) {
    std::vector<T> bytes;
    if (file == nullptr) {
        perror("Failed to open file");
        return bytes; // Return empty vector on error
    }
    T byte;
    while (fread(&byte, sizeof(T), 1, file) == 1) {
        bytes.push_back(byte);
    }
    fclose(file);
    return bytes; // Return the vector of bytes
}

template <typename Container>
size_t arg_max(Container const& c) {
    return static_cast<size_t>(std::distance(std::begin(c), std::max_element(std::begin(c), std::end(c))));
}

template <typename Container>
size_t arg_min(Container const& c) {
    return static_cast<size_t>(std::distance(std::begin(c), std::min_element(std::begin(c), std::end(c))));
}