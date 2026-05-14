#include <array>
#include <string>
#include <cstdio>
#include <optional>
#include <vector>
#include "mnist_if.hpp"
#include "soct/soct_ff.h"

static mnist_if_t mnist_if{};

static const char *s_sdc_root = SOCT_SD_PATH;
static const char *s_default_dir = SOCT_SD_PATH "/data";


void enable_fpu() {
    uint64_t mstatus;
    asm volatile ("csrr %0, mstatus" : "=r" (mstatus));
    mstatus |= 0x6000;
    asm volatile ("csrw mstatus, %0" :: "r" (mstatus));
}

int run(const std::string &data_dir) {
    std::vector<std::string> image_paths;
    std::vector<uint8_t> labels;

    FILE *tmp = nullptr;
    int correct = 0;

    tmp = fopen((data_dir + "image_paths.txt").c_str(), "r");
    if (tmp != nullptr) {
        image_paths = f2str(tmp);
    } else {
        perror("Failed to open image paths file");
        return 1;
    }

    tmp = fopen((data_dir + "labels.bin").c_str(), "rb");
    if (tmp != nullptr) {
        labels = f2b<uint8_t>(tmp);
    } else {
        perror("Failed to open labels file");
        return 1;
    }

    if (labels.size() != image_paths.size()) {
        printf("Number of labels (%lu) does not match number of images (%lu)\n", labels.size(), image_paths.size());
        return 1;
    }

    const size_t num_images = image_paths.size();

    for (size_t i = 0; i < num_images; ++i) {
        const std::string abs_image_path = data_dir + image_paths[i];
        const auto dist = mnist_if.infer(abs_image_path);
        if (!dist.has_value()) {
            printf("Failed to infer mnist\n");
            return 1;
        }
        const auto argmax = arg_max(dist.value());
        printf("Image %s: expected %u, got %zu\n", abs_image_path.c_str(), labels[i], argmax);
        if (labels[i] == argmax) {
            correct++;
        }
    }

    printf("Accuracy: %.2f%% (%d/%lu)\n", 100.0 * static_cast<double>(correct) / static_cast<double>(num_images),
           correct, num_images);

    return 0;
}

int main(const int argc, char **argv) {
    enable_fpu();
    std::string data_dir = s_default_dir; // by default, load from sd card
    if (argc > 1) {
        data_dir = argv[1];
    }
    if (data_dir.back() != '/') {
        data_dir += '/';
    }
    return run(data_dir);
}
