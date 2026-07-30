/*
 * The shared MNIST contract of the gemmini-mnist program family - one model, several
 * execution contexts (the bare-metal variant in binaries/baremetal/gemmini-mnist, the
 * Linux variant in binaries/linux/userspace/gemmini-mnist). Compiles as C and C++.
 *
 * DATATYPE: `elem_t` comes from the WORKSPACE's gemmini_params.h - the generator writes
 * that header from the elaborated Gemmini configuration (soct.config.gemmini.Configs
 * calls config.generateHeader()), so software and systolic array always agree: int8 for
 * the integer configs, float for the Fp ones. Images on disk are raw elem_t arrays of
 * MNIST_PIXELS values (shared/mnist/generate.py produces them next to the model).
 *
 * MODEL: shared/mnist/model.h (onnx2c-generated) exposes
 *   void entry(const elem_t in[1][MNIST_PIXELS], elem_t out[1][MNIST_CLASSES]);
 * Its weights are float, so it requires an FP Gemmini config (guard below).
 *
 * EXTENDING: another model = another model header exposing the same entry() shape plus
 * its own dims next to this one; another datatype = regenerate the model for the target
 * config's elem_t (quantized weights for int8) - nothing in the program shells assumes
 * float beyond the guard, which then moves into the model that needs it.
 */
#ifndef SOCT_MNIST_COMMON_H
#define SOCT_MNIST_COMMON_H

#include <stddef.h>

#include "include/gemmini.h"

#ifndef ELEM_T_IS_FLOAT
#error "shared/mnist/model.h carries float weights and needs an FP Gemmini config (e.g. soct.RocketB1Gem4Fp). For an integer config, regenerate the model with quantized weights for its elem_t."
#endif

enum {
    MNIST_WIDTH = 28,
    MNIST_HEIGHT = 28,
    MNIST_PIXELS = MNIST_WIDTH * MNIST_HEIGHT,
    MNIST_CLASSES = 10
};

static inline size_t mnist_argmax(const elem_t *scores, size_t n) {
    size_t best = 0;
    for (size_t i = 1; i < n; i++) {
        if (scores[i] > scores[best])
            best = i;
    }
    return best;
}

#endif
