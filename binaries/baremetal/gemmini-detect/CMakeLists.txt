cmake_minimum_required(VERSION 3.20)

# Digit detection on the monitor: the shared MNIST model (matmuls on Gemmini)
# slides over a generated scene, detections become bounding boxes via soctfb.
# Needs an FP Gemmini video design (e.g. soct.RocketB1Gem4Fp with
# soct.WithIncoherentVideoStream); regenerate the scene with
#   shared/mnist/generate.py --scene binaries/baremetal/gemmini-detect/scene.h
include(${CMAKE_CURRENT_LIST_DIR}/../soctfb/soctfb.cmake)

list(APPEND CMAKE_C_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/main.c)

include(${CMAKE_CURRENT_LIST_DIR}/../soctglue-static.cmake)

target_compile_options(${SOCT_PROGRAM} PRIVATE -std=gnu11 -O3 -ffast-math)
target_include_directories(${SOCT_PROGRAM} PRIVATE
        ${SOCT_SYSTEM_ROOT}/gemmini-rocc-tests
        # The model, the datatype contract and the scene generator's home.
        ${SOCETEER_ROOT}/shared/mnist
)

soctfb_apply(${SOCT_PROGRAM})
